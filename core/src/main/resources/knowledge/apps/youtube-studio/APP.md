---
name: youtube-studio
display: YouTube Studio
packages: ["com.google.android.apps.youtube.creator"]
aliases: ["YouTube Studio", "YT Studio", "Studio", "yt studio"]
sites: []
open: YouTube Studio
description: Manage YouTube videos, check upload status and analytics
---
## Overview
YouTube Studio is Google's channel management application for creators, package `com.google.android.apps.youtube.creator`.

## Interface
The bottom navigation bar contains Dashboard, Content, Analytics, Comments, and Earn. The Content tab lists Videos, Shorts, Live, and Playlists, displaying title, thumbnail, publication/upload date, visibility badge (Public, Unlisted, Private, Processing, Draft), views, and comments.

## Features
- **Content Tab**: Lists all channel videos and Shorts in chronological order.
- **Upload & Processing Status**: Displays whether a newly uploaded video is still uploading, processing (SD/HD processing), checks running (copyright checks), or published.
- **Video Visibility & Details**: Tapping an item displays its details and allows checking visibility status, description, restrictions, or copying the video link (`https://youtu.be/...`).

## Known Behaviors
- Freshly uploaded videos might show "Processing" or "Draft" for a brief period before final visibility is applied.
- If network disconnection happens during upload, YouTube Studio displays the video status or partial upload so you can inspect before attempting a re-upload.
