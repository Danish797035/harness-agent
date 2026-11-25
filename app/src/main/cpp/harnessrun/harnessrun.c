/**
 * harnessrun — Harness sandbox launcher. SPDX-License-Identifier: Apache-2.0 Copyright (c) 2026 Mina
 * authors
 *
 * Usage: libharnessrun.so [--timeout <seconds,1-3600>] [--net on|off] [--nice <0-19, default 10>] [--max-procs
 * <Default 256>] [--max-file-mb <Default 2048>] -- <Program> [parameters...] libharnessrun.so --self-test output
 * "harnessrun ok" and "seccomp ok" or "seccomp unavailable: <reason>",exit 0
 *
 * If --net is not given, press off (disconnection is the default value). There is no time limit when
 * --timeout is not given. Exit code: The exit code of the command itself; timeout 124; command killed by
 * signal N 128+N; error in the launcher itself 125, the reason is written in the last line of standard
 * error, starting with "harnessrun:".
 */

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <libgen.h>
#include <limits.h>
#include <signal.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>

#ifdef __ANDROID__
#include <android/api-level.h>
#endif

#define EXIT_TIMEOUT 124
#define EXIT_LAUNCHER_ERROR 125

/** The system call number of aarch64 (the filter only recognizes this architecture) */
#define NR_IOCTL_AARCH64 29
#define NR_SOCKET_AARCH64 198
#define NR_IO_URING_SETUP_AARCH64 425
#define NR_PIDFD_SEND_SIGNAL_AARCH64 424
#define NR_PIDFD_OPEN_AARCH64 434
#define NR_CLOSE_RANGE_AARCH64 436
#define BINDER_WRITE_READ_CMD 0xc0306201u /* _IOWR('b', 1, struct binder_write_read), 64-bit */

#define GRACE_MS 2000          /* How long to wait after SIGTERM and then SIGKILL? */
#define POLL_MS 50
#define KILL_ROUNDS_MAX 200    /* SIGKILL The upper limit of the loop (about 10 seconds) to prevent never exiting under abnormal circumstances */
#define SCAN_MAX 32768

struct options {
	int self_test;
	long timeout_sec;  /* 0 = no time limit */
	int net_off;
	long nice;
	long max_procs;
	long max_file_mb;
	char **command; /* Terminated by NULL, pointing to the inside of argv */
};

static int fail(const char *what, const char *detail)
{
	if (detail != NULL)
		fprintf(stderr, "harnessrun: %s: %s\n", what, detail);
	else
		fprintf(stderr, "harnessrun: %s\n", what);
	return EXIT_LAUNCHER_ERROR;
}

static int parse_long(const char *name, const char *text, long min, long max, long *out)
{
	char *end;
	long v;

	if (text == NULL)
		return fail("missing value for", name), -1;
	errno = 0;
	v = strtol(text, &end, 10);
	if (errno != 0 || end == text || *end != '\0' || v < min || v > max) {
		fprintf(stderr, "harnessrun: invalid value for %s: %s (allowed %ld-%ld)\n", name, text, min, max);
		return -1;
	}
	*out = v;
	return 0;
}

/** Returns 0 for success; if it is not 0, the error message has been written. */
static int parse_options(int argc, char **argv, struct options *opts)
{
	int i = 1;

	memset(opts, 0, sizeof(*opts));
	opts->net_off = 1;
	opts->nice = 10;
	opts->max_procs = 256;
	opts->max_file_mb = 2048;
	for (; i < argc; i++) {
		const char *arg = argv[i];
		const char *value = i + 1 < argc ? argv[i + 1] : NULL;
		long *target = NULL;
		long min = 0, max = 0;

		if (strcmp(arg, "--") == 0) {
			i++;
			break;
		}
		if (strcmp(arg, "--self-test") == 0) {
			opts->self_test = 1;
			continue;
		}
		if (strcmp(arg, "--net") == 0) {
			if (value != NULL && strcmp(value, "on") == 0)
				opts->net_off = 0;
			else if (value != NULL && strcmp(value, "off") == 0)
				opts->net_off = 1;
			else
				return fail("invalid value for --net (on|off)", value), -1;
			i++;
			continue;
		}
		if (strcmp(arg, "--timeout") == 0) {
			target = &opts->timeout_sec; min = 1; max = 3600;
		} else if (strcmp(arg, "--nice") == 0) {
			target = &opts->nice; min = 0; max = 19;
		} else if (strcmp(arg, "--max-procs") == 0) {
			target = &opts->max_procs; min = 1; max = 65536;
		} else if (strcmp(arg, "--max-file-mb") == 0) {
			target = &opts->max_file_mb; min = 1; max = 1048576;
		}
		if (target != NULL) {
			if (parse_long(arg, value, min, max, target) != 0)
				return -1;
			i++;
			continue;
		}
		if (strncmp(arg, "--", 2) == 0)
			return fail("unknown option", arg), -1;
		break; /* The first non-option parameter is the command */
	}

	if (opts->self_test)
		return 0;
	if (i >= argc)
		return fail("missing command", NULL), -1;
	opts->command = &argv[i];
	return 0;
}

/** ---------- Disconnection: seccomp filter ---------- */

/**
 * Whitelist: socket() only allows AF_UNIX (used for bridges) and AF_NETLINK (checks the local network
 * card, routing, etc., but does not leave the local machine), and other address families → EACCES;
 * io_uring_setup → ENOSYS (it can bypass socket filtering); binder's BINDER_WRITE_READ ioctl → EACCES
 * (data can also be taken out through system services, and binder is not originally bound to PRoot)
 * Equipment, one more block will be blocked here); the rest will be allowed to pass. All non-aarch64
 * system calls (32-bit compatibility layer) will kill the process to prevent bypassing by changing the
 * call number. Filters are inherited with fork/exec and cannot be revoked.
 */
static int install_net_filter(char *reason, size_t reason_len)
{
	struct sock_filter filter[] = {
		/* 0 */ BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)),
		/* 1 */ BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AUDIT_ARCH_AARCH64, 1, 0),
		/* 2 */ BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS),
		/* 3 */ BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
		/* 4 */ BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, NR_IO_URING_SETUP_AARCH64, 0, 1),
		/* 5 */ BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | (ENOSYS & SECCOMP_RET_DATA)),
		/* 6 */ BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, NR_SOCKET_AARCH64, 2, 0),          /* → 9 */
		/* 7 */ BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, NR_IOCTL_AARCH64, 6, 0),           /* → 14 */
		/* 8 */ BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
		/** Little endian: the lower 32 bits of the parameter come first; the domain of the socket and the cmd of the ioctl are both 32 bits, and the kernel only looks at the lower 32 bits. */
		/* 9 */ BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[0])),
		/* 10 */ BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AF_UNIX, 2, 0),                  /* → 13 */
		/* 11 */ BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AF_NETLINK, 1, 0),               /* → 13 */
		/* 12 */ BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | (EACCES & SECCOMP_RET_DATA)),
		/* 13 */ BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
		/* 14 */ BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, args[1])),
		/* 15 */ BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, BINDER_WRITE_READ_CMD, 0, 1),
		/* 16 */ BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | (EACCES & SECCOMP_RET_DATA)),
		/* 17 */ BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
	};
	struct sock_fprog prog = {
		.len = (unsigned short)(sizeof(filter) / sizeof(filter[0])),
		.filter = filter,
	};

	if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) {
		snprintf(reason, reason_len, "PR_SET_NO_NEW_PRIVS: %s", strerror(errno));
		return -1;
	}
	if (prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog, 0, 0) != 0) {
		snprintf(reason, reason_len, "PR_SET_SECCOMP: %s", strerror(errno));
		return -1;
	}
	return 0;
}

/** Install the filter in the child process and test it: AF_INET is rejected, AF_UNIX is available. Returns 0 if available, otherwise reason writes the reason. */
static int probe_net_filter(char *reason, size_t reason_len)
{
	int fds[2];
	pid_t pid;
	int status;
	ssize_t n;

	if (pipe(fds) != 0) {
		snprintf(reason, reason_len, "pipe: %s", strerror(errno));
		return -1;
	}
	pid = fork();
	if (pid < 0) {
		snprintf(reason, reason_len, "fork: %s", strerror(errno));
		close(fds[0]);
		close(fds[1]);
		return -1;
	}
	if (pid == 0) {
		char msg[128] = "";
		int s;

		close(fds[0]);
		if (install_net_filter(msg, sizeof(msg)) == 0) {
			s = socket(AF_INET, SOCK_STREAM, 0);
			if (s >= 0)
				snprintf(msg, sizeof(msg), "filter installed but AF_INET socket still allowed");
			else if (errno != EACCES)
				snprintf(msg, sizeof(msg), "AF_INET socket failed with %s, expected EACCES", strerror(errno));
			else if ((s = socket(AF_UNIX, SOCK_STREAM, 0)) < 0)
				snprintf(msg, sizeof(msg), "AF_UNIX socket blocked: %s", strerror(errno));
		}
		if (msg[0] != '\0' && write(fds[1], msg, strlen(msg)) < 0)
			_exit(2);
		_exit(msg[0] == '\0' ? 0 : 1);
	}
	close(fds[1]);
	n = read(fds[0], reason, reason_len - 1);
	close(fds[0]);
	reason[n > 0 ? n : 0] = '\0';
	while (waitpid(pid, &status, 0) < 0 && errno == EINTR)
		;
	if (WIFSIGNALED(status)) {
		snprintf(reason, reason_len, "probe killed by signal %d", WTERMSIG(status));
		return -1;
	}
	if (!WIFEXITED(status) || WEXITSTATUS(status) != 0) {
		if (reason[0] == '\0')
			snprintf(reason, reason_len, "probe failed");
		return -1;
	}
	return 0;
}

static int self_test(void)
{
	char reason[160];

	if (printf("harnessrun ok\n") < 0 || fflush(stdout) != 0)
		return fail("self-test", "cannot write stdout");
	if (probe_net_filter(reason, sizeof(reason)) == 0)
		printf("seccomp ok\n");
	else
		printf("seccomp unavailable: %s\n", reason);
	if (fflush(stdout) != 0)
		return fail("self-test", "cannot write stdout");
	return 0;
}

/** ---------- /proc scan ---------- */

/**
 * The seccomp policy installed by Android for the App sends SIGSYS (directly kills the process) to
 * system calls it does not recognize, instead of returning ENOSYS. pidfd_open/pidfd_send_signal is only
 * included in the policy from API 31 and close_range onwards from API 34, and cannot be tried on earlier
 * systems.
 */
static int api_level(void)
{
#ifdef __ANDROID__
	return android_get_device_api_level();
#elif defined(HARNESSRUN_HOST_API_LEVEL)
	return HARNESSRUN_HOST_API_LEVEL;
#elif defined(MINARUN_HOST_API_LEVEL)
	return MINARUN_HOST_API_LEVEL; /* Only used to test fallback on ordinary Linux */
#else
	return 10000;
#endif
}

/** Read the parent process number and thread number in /proc/<pid>/stat. Returns 0 on success. */
static int read_stat(int proc_fd, const char *pid_name, pid_t *ppid, long *threads)
{
	char path[64], buf[1024];
	const char *p;
	ssize_t n;
	int fd, pp;
	long nt;
	char state;

	snprintf(path, sizeof(path), "%s/stat", pid_name);
	fd = openat(proc_fd, path, O_RDONLY | O_CLOEXEC);
	if (fd < 0)
		return -1;
	n = read(fd, buf, sizeof(buf) - 1);
	close(fd);
	if (n <= 0)
		return -1;
	buf[n] = '\0';
	p = strrchr(buf, ')'); /* There may be spaces and parentheses in the process name, read from the last ')' onwards */
	if (p == NULL)
		return -1;
	/** Field 3 state, 4 ppid,...,20 num_threads */
	if (sscanf(p + 2, "%c %d %*d %*d %*d %*d %*u %*u %*u %*u %*u %*u %*u %*d %*d %*d %*d %ld",
		   &state, &pp, &nt) != 3)
		return -1;
	*ppid = pp;
	*threads = nt;
	return 0;
}

static int is_pid_name(const char *s)
{
	if (*s == '\0')
		return 0;
	for (; *s != '\0'; s++)
		if (*s < '0' || *s > '9')
			return 0;
	return 1;
}

/** The current number of tasks of this identity (uid) (threads are also counted, RLIMIT_NPROC is counted by tasks). */
static long count_user_tasks(void)
{
	DIR *dir = opendir("/proc");
	struct dirent *e;
	uid_t uid = getuid();
	long total = 0;

	if (dir == NULL)
		return -1;
	while ((e = readdir(dir)) != NULL) {
		struct stat st;
		pid_t ppid;
		long threads;

		if (!is_pid_name(e->d_name))
			continue;
		if (fstatat(dirfd(dir), e->d_name, &st, 0) != 0 || st.st_uid != uid)
			continue;
		if (read_stat(dirfd(dir), e->d_name, &ppid, &threads) == 0)
			total += threads;
	}
	closedir(dir);
	return total;
}

static pid_t scan_pid[SCAN_MAX];
static pid_t scan_ppid[SCAN_MAX];
static unsigned char scan_mark[SCAN_MAX];
static int scan_count;
static int pidfd_usable = -1; /* -1 unknown, 0 not available, 1 available */

/** Read /proc/<pid>/stat again: the parent process is still this process, or a descendant identified by this round of scanning. */
static int still_descendant(pid_t pid)
{
	char name[32];
	pid_t ppid;
	long threads;
	int i;

	snprintf(name, sizeof(name), "/proc/%d", (int)pid);
	if (read_stat(AT_FDCWD, name, &ppid, &threads) != 0)
		return 0;
	if (ppid == getpid())
		return 1;
	for (i = 0; i < scan_count; i++)
		if (scan_mark[i] && scan_pid[i] == ppid)
			return 1;
	return 0;
}

/**
 * Between scanning and signaling, the process number may have been recycled and reused by other
 * processes with the same identity (Mina itself). When there is pidfd: first use pidfd_open to pin the
 * process identity, then confirm that it is still a descendant, and then send a signal through pidfd -
 * no accidental killing. When there is no pidfd, you can only confirm once before kill (the window is
 * very small); kill_tree also SIGSTOPs the entire tree first and then rescans the stopped process. The
 * stopped process will not recycle the child process, and the process number after the rescan is stable.
 */
static void send_to_descendant(pid_t pid, int sig)
{
	if (pidfd_usable < 0)
		pidfd_usable = api_level() >= 31;
	if (pidfd_usable) {
		int fd = (int)syscall(NR_PIDFD_OPEN_AARCH64, pid, 0);
		if (fd >= 0) {
			if (still_descendant(pid))
				syscall(NR_PIDFD_SEND_SIGNAL_AARCH64, fd, sig, NULL, 0);
			close(fd);
			return;
		}
		if (errno == ESRCH)
			return;
		pidfd_usable = 0; /* The kernel is too old (ENOSYS), etc.: Change to the following retreat */
	}
	if (still_descendant(pid))
		kill(pid, sig);
}

/**
 * Find all the processes that the parent process chain ultimately points to this process (this process
 * is the reaper of the child process, and the descendants that leave the parent process will also hang
 * back), and send signals to them when sig is non-0. Returns the number found (including zombies that
 * have not been recovered); when the scan table is full, one more is counted and treated as "there is
 * still".
 */
static int signal_descendants(int sig)
{
	DIR *dir = opendir("/proc");
	struct dirent *e;
	pid_t self = getpid();
	int n = 0, found = 0, full, changed, i;

	if (dir == NULL)
		return 1; /* If you can’t see it, just pretend it’s there and let the caller continue to wait. */
	while ((e = readdir(dir)) != NULL && n < SCAN_MAX) {
		pid_t ppid;
		long threads;

		if (!is_pid_name(e->d_name))
			continue;
		if (read_stat(dirfd(dir), e->d_name, &ppid, &threads) != 0)
			continue;
		scan_pid[n] = (pid_t)atoi(e->d_name);
		scan_ppid[n] = ppid;
		scan_mark[n] = ppid == self;
		n++;
	}
	full = e != NULL; /* It’s full before I finish reading it. */
	closedir(dir);

	do {
		changed = 0;
		for (i = 0; i < n; i++) {
			int j;
			if (scan_mark[i])
				continue;
			for (j = 0; j < n; j++) {
				if (scan_mark[j] && scan_pid[j] == scan_ppid[i]) {
					scan_mark[i] = 1;
					changed = 1;
					break;
				}
			}
		}
	} while (changed);

	scan_count = n;
	for (i = 0; i < n; i++) {
		if (!scan_mark[i])
			continue;
		found++;
		if (sig != 0)
			send_to_descendant(scan_pid[i], sig);
	}
	return found + full;
}

/** ---------- Operation and Clearance ---------- */

/** Recycle all terminated direct child processes (including orphans that hang back); the status of the main child process is recorded. */
static void reap(pid_t child, int *status, int *have_status)
{
	pid_t p;
	int st;

	while ((p = waitpid(-1, &st, WNOHANG)) > 0) {
		if (p == child) {
			*status = st;
			*have_status = 1;
		}
	}
}

static void sleep_ms(long ms)
{
	struct timespec ts = { ms / 1000, (ms % 1000) * 1000000L };
	while (nanosleep(&ts, &ts) != 0 && errno == EINTR)
		;
}

/** First send SIGTERM to the process group and all descendants, wait for GRACE_MS; SIGKILL the descendants that are still there, and loop until there is no one left. */
static void kill_tree(pid_t child, int *status, int *have_status)
{
	long waited;
	int round;

	reap(child, status, have_status);
	if (signal_descendants(0) == 0)
		return;
	kill(-getpid(), SIGTERM); /* This process is also in the group; SIGTERM has been blocked and does not affect itself. */
	signal_descendants(SIGTERM);
	for (waited = 0; waited < GRACE_MS; waited += POLL_MS) {
		sleep_ms(POLL_MS);
		reap(child, status, have_status);
		if (signal_descendants(0) == 0)
			return;
	}
	for (round = 0; round < KILL_ROUNDS_MAX; round++) {
		if (!pidfd_usable)
			signal_descendants(SIGSTOP); /* Freeze the entire tree first, and the process number obtained by rescanning below will not change again. */
		if (signal_descendants(SIGKILL) == 0)
			return;
		sleep_ms(POLL_MS);
		reap(child, status, have_status);
	}
	fprintf(stderr, "harnessrun: warning: descendants still present after SIGKILL\n");
}

static long now_ms(void)
{
	struct timespec ts;
	clock_gettime(CLOCK_MONOTONIC, &ts);
	return ts.tv_sec * 1000L + ts.tv_nsec / 1000000L;
}

/**
 * When PROOT_LOADER is not set, PRoot will write the embedded loader into a temporary directory before
 * executing it (Android prohibits the execution of files in the data directory), so before starting
 * libproot.so, PROOT_LOADER is required to point to a file in the same directory as the launcher
 * (nativeLibraryDir). Returns 0 if passed; if not 0, the error message has been written.
 */
static int check_proot_loader(const char *program)
{
	char path[PATH_MAX], self[PATH_MAX], loader[PATH_MAX], self_dir[PATH_MAX];
	const char *env = getenv("PROOT_LOADER");
	ssize_t n;

	snprintf(path, sizeof(path), "%s", program);
	if (strcmp(basename(path), "libproot.so") != 0)
		return 0;
	if (env == NULL || env[0] == '\0' || realpath(env, loader) == NULL)
		return fail("PROOT_LOADER missing", env);
	n = readlink("/proc/self/exe", self, sizeof(self) - 1);
	if (n < 0)
		return fail("cannot read /proc/self/exe", strerror(errno));
	self[n] = '\0';
	/** bionic's dirname returns the shared buffer, copy out a copy first */
	snprintf(self_dir, sizeof(self_dir), "%s", dirname(self));
	snprintf(path, sizeof(path), "%s", loader);
	if (strcmp(basename(path), "libproot_loader.so") != 0)
		return fail("PROOT_LOADER missing", "not libproot_loader.so");
	if (strcmp(self_dir, dirname(loader)) != 0)
		return fail("PROOT_LOADER missing", "not in the launcher directory");
	return 0;
}

/** The upper limit of file size and number of processes; the soft and hard limits are set together and do not exceed the original hard limit. */
static int set_limit(int resource, rlim_t value, const char *name)
{
	struct rlimit rl;

	if (getrlimit(resource, &rl) != 0)
		return fail(name, strerror(errno));
	if (rl.rlim_max != RLIM_INFINITY && value > rl.rlim_max)
		value = rl.rlim_max;
	rl.rlim_cur = value;
	rl.rlim_max = value;
	if (setrlimit(resource, &rl) != 0)
		return fail(name, strerror(errno));
	return 0;
}

/** oom_score_adj can only be adjusted higher (ordinary identity); forget it if you can't write it in. */
static void set_oom_score_adj(const char *value)
{
	int fd = open("/proc/self/oom_score_adj", O_WRONLY | O_CLOEXEC);
	if (fd >= 0) {
		ssize_t ignored = write(fd, value, strlen(value));
		(void)ignored;
		close(fd);
	}
}

/** Turn off the inherited descriptors other than 0/1/2, and the command cannot get any files or sockets of Mina. */
static void close_inherited_fds(void)
{
	DIR *dir;
	struct dirent *e;

	if (api_level() >= 34 && syscall(NR_CLOSE_RANGE_AARCH64, 3, ~0U, 0) == 0)
		return;
	dir = opendir("/proc/self/fd");
	if (dir == NULL)
		return;
	while ((e = readdir(dir)) != NULL) {
		int fd;
		if (!is_pid_name(e->d_name))
			continue;
		fd = atoi(e->d_name);
		if (fd >= 3 && fd != dirfd(dir))
			close(fd);
	}
	closedir(dir);
}

/** Self-contained session, reaper, parent death signal, priority, OOM score, resource limit, network disconnection. Returns 0 on success. */
static int prepare(const struct options *opts)
{
	pid_t parent = getppid();
	char reason[160];
	long used;

	/** The parent process died before reading the PPID: it has been adopted to init, and the parent death signal will never come again. */
	if (parent == 1)
		return fail("parent exited", NULL);
	if (prctl(PR_SET_PDEATHSIG, SIGTERM, 0, 0, 0) != 0)
		return fail("PR_SET_PDEATHSIG", strerror(errno));
	if (getppid() != parent) /* The parent process has disappeared before setting it. */
		return fail("parent exited", NULL);
	if (setsid() < 0 && getsid(0) != getpid())
		return fail("setsid", strerror(errno));
	if (prctl(PR_SET_CHILD_SUBREAPER, 1, 0, 0, 0) != 0)
		return fail("PR_SET_CHILD_SUBREAPER", strerror(errno));

	/**
	 * Priority and OOM scores only protect Mina itself, but cannot prevent commands. Launcher itself 900,
	 * command 1000 (adjusted in the child process): When the memory is insufficient, kill the command
	 * first, and the launcher can be cleared only when it is alive.
	 */
	setpriority(PRIO_PROCESS, 0, (int)opts->nice);
	set_oom_score_adj("900");

	if (set_limit(RLIMIT_FSIZE, (rlim_t)opts->max_file_mb << 20, "RLIMIT_FSIZE") != 0)
		return -1;
	/**
	 * RLIMIT_NPROC is counted by the kernel as a whole by identity (uid): Mina's threads and other sandbox
	 * commands running at the same time all occupy the same quota, so the upper limit = the number of tasks
	 * already in the identity at the time of setting + max-procs, instead of max-procs for this command
	 * alone.
	 */
	used = count_user_tasks();
	if (used < 0)
		return fail("cannot count processes", strerror(errno));
	if (set_limit(RLIMIT_NPROC, (rlim_t)(used + opts->max_procs), "RLIMIT_NPROC") != 0)
		return -1;

	if (opts->net_off && install_net_filter(reason, sizeof(reason)) != 0)
		return fail("network isolation unavailable", reason);
	return 0;
}

static int run(const struct options *opts)
{
	sigset_t wait_set, orig_set;
	int status = 0, have_status = 0, timed_out = 0, terminated = 0;
	long deadline = 0;
	pid_t child;

	if (check_proot_loader(opts->command[0]) != 0)
		return EXIT_LAUNCHER_ERROR;
	/** Shield the signal you want to wait for first, and then do anything else to avoid the signal arriving before sigtimedwait and being lost. */
	sigemptyset(&wait_set);
	sigaddset(&wait_set, SIGCHLD);
	sigaddset(&wait_set, SIGTERM);
	sigaddset(&wait_set, SIGINT);
	sigaddset(&wait_set, SIGHUP);
	if (sigprocmask(SIG_BLOCK, &wait_set, &orig_set) != 0)
		return fail("sigprocmask", strerror(errno));
	if (prepare(opts) != 0)
		return EXIT_LAUNCHER_ERROR;

	child = fork();
	if (child < 0)
		return fail("fork", strerror(errno));
	if (child == 0) {
		sigprocmask(SIG_SETMASK, &orig_set, NULL);
		/**
		 * When the launcher is directly SIGKILLed, there is no chance to clear it: PRoot relies on PDEATHSIG
		 * to follow and die. PRoot is patched with PTRACE_O_EXITKILL. Once it dies, all tracked processes will
		 * be killed by the kernel. When the entire Mina process is killed by the system, Android presses the
		 * App's cgroup to kill the processes in the same cgroup (including all sandbox processes here). This
		 * is the last step.
		 */
		prctl(PR_SET_PDEATHSIG, SIGKILL, 0, 0, 0);
		set_oom_score_adj("1000");
		close_inherited_fds();
		execv(opts->command[0], opts->command);
		fprintf(stderr, "harnessrun: exec failed: %s\n", strerror(errno));
		_exit(EXIT_LAUNCHER_ERROR);
	}

	if (opts->timeout_sec > 0)
		deadline = now_ms() + opts->timeout_sec * 1000L;
	for (;;) {
		struct timespec ts, *tsp = NULL;
		int sig;

		reap(child, &status, &have_status);
		if (have_status)
			break;
		if (deadline != 0) {
			long left = deadline - now_ms();
			if (left <= 0) {
				timed_out = 1;
				break;
			}
			ts.tv_sec = left / 1000;
			ts.tv_nsec = (left % 1000) * 1000000L;
			tsp = &ts;
		}
		sig = sigtimedwait(&wait_set, NULL, tsp);
		if (sig == SIGTERM || sig == SIGINT || sig == SIGHUP) {
			terminated = sig;
			break;
		}
		/** SIGCHLD, timeout (EAGAIN), EINTR: Go back to the beginning of the loop and recheck */
	}

	/** Also clear it when the command ends normally: processes left in the background cannot survive this command. */
	kill_tree(child, &status, &have_status);
	if (timed_out)
		return EXIT_TIMEOUT;
	if (have_status) {
		if (WIFEXITED(status))
			return WEXITSTATUS(status);
		if (WIFSIGNALED(status))
			return 128 + WTERMSIG(status);
	}
	return 128 + terminated;
}

int main(int argc, char **argv)
{
	struct options opts;

	if (parse_options(argc, argv, &opts) != 0)
		return EXIT_LAUNCHER_ERROR;
	if (opts.self_test)
		return self_test();
	return run(&opts);
}
