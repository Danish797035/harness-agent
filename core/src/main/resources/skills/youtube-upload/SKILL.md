---
name: youtube-upload
display: YouTube Upload
description: Upload video or Short to YouTube and confirm in Studio
aliases: ["youtube pe daalo", "video upload karo", "short upload karo", "upload video"]
apps: ["youtube", "youtube-studio"]
terms: ["youtube", "upload", "video", "short", "shorts", "yt", "studio", "यूट्यूब", "अपलोड"]
---
## Overview
Upload video files or Shorts to YouTube via the YouTube app, ensure correct metadata and visibility settings, confirm details with the user before finalizing, and verify post-upload status and retrieve video link using YouTube Studio or "Your videos".

## Steps
1. **Select Video**:
   - If the user specified a video name, timestamp, or description, find that exact video. Otherwise, choose the newest/latest video in the gallery.
   - Check duration and aspect ratio: vertical videos under 60 seconds must be uploaded as Shorts; videos longer than 60 seconds or horizontal aspect ratio are uploaded as regular videos.
2. **Draft Metadata (Title, Description, Hashtags)**:
   - Draft a concise, descriptive title (strictly under 100 characters).
   - Draft a relevant description and include 3 to 5 relevant hashtags in the user's language (default to Hinglish if user speaks Hindi/Hinglish). If the user provided custom title/description, use them verbatim.
3. **Visibility Settings**:
   - Default visibility MUST be set to **Private**.
   - Set to Public or Unlisted ONLY when the user explicitly requests it.
4. **Audience Setting**:
   - Under Audience ("Is this video made for kids?"), select **"No, it's not made for kids"** unless the user explicitly requested otherwise.
5. **Mandatory Confirmation**:
   - **MUST PAUSE** and ask the user for explicit confirmation before tapping the final "Upload" button.
   - Present the chosen video filename/timestamp, title, description, and visibility setting clearly to the user and wait for approval.
6. **Post-Upload Verification & Link Retrieval**:
   - After upload initiates, open YouTube Studio (or YouTube -> You -> "Your videos") to verify the video appears in the list.
   - Inspect the upload/processing status and current visibility.
   - Copy or extract the video link and provide it to the user.

## Common Pitfalls
- **Selecting Wrong Video**: Picking the wrong clip from the gallery or misidentifying similar recent clips. Always verify video details.
- **Shorts vs Regular Video Confusion**: Treating vertical short clips (<60s) as regular videos or attempting to force standard video settings onto Shorts.
- **Unexpected Popups**: First-time channel creation, phone verification prompts, or copyright alerts.
- **Network Interruption**: On unstable networks, upload may stall. Never start a duplicate upload blindly; first check YouTube Studio or "Your videos" to see if the upload is resuming or in progress.
- **Accidental Public Release**: Forgetting to keep visibility as Private when not explicitly instructed by the user.

## Verification & Evidence
- The video appears in YouTube Studio Content tab or YouTube "Your videos".
- The upload/processing status is confirmed (uploaded or processing).
- The correct visibility (Private, Unlisted, or Public) is applied.
- The shareable video URL is retrieved and returned to the user.

## When to Hand Over to User
- The YouTube app is not signed in or requires account switching.
- Phone number SMS verification or channel creation dialog appears.
- A copyright strike, audio restriction, or content violation notice is triggered.
- Multiple ambiguous candidate videos exist in the gallery and the agent cannot determine the intended video with certainty.
