export class AccountMutex {
  private activeLocks: Map<string, Promise<void>> = new Map();
  private cooldowns: Map<string, number> = new Map();

  async acquire(accountId: string): Promise<() => void> {
    const now = Date.now();
    const cooldownUntil = this.cooldowns.get(accountId) || 0;
    if (now < cooldownUntil) {
      const waitTimeSec = Math.ceil((cooldownUntil - now) / 1000);
      throw new Error(`Account "${accountId}" is cooling down. Retry after ${waitTimeSec}s.`);
    }

    while (this.activeLocks.has(accountId)) {
      await this.activeLocks.get(accountId);
    }

    let releaseLock: () => void;
    const lockPromise = new Promise<void>((resolve) => {
      releaseLock = resolve;
    });

    this.activeLocks.set(accountId, lockPromise);

    return () => {
      this.activeLocks.delete(accountId);
      releaseLock();
    };
  }

  setCooldown(accountId: string, durationMs: number) {
    this.cooldowns.set(accountId, Date.now() + durationMs);
  }

  isCoolingDown(accountId: string): boolean {
    return Date.now() < (this.cooldowns.get(accountId) || 0);
  }

  getCooldownStatus(): Record<string, { cooling: boolean; remainingSec: number }> {
    const status: Record<string, { cooling: boolean; remainingSec: number }> = {};
    const now = Date.now();
    for (const [acct, until] of this.cooldowns.entries()) {
      if (now < until) {
        status[acct] = { cooling: true, remainingSec: Math.ceil((until - now) / 1000) };
      }
    }
    return status;
  }
}

export const accountMutex = new AccountMutex();
