---
name: youtube
display: YouTube
packages: ["com.google.android.youtube"]
aliases: ["YouTube", "yt", "youtube", "यूट्यूब"]
sites: ["youtube.com"]
open: YouTube
description: Video sharing and streaming app; upload videos and Shorts
---
## Overview
YouTube is Google's video sharing platform, package `com.google.android.youtube`. The website is youtube.com.

## Interface
The bottom navigation bar has Home, Shorts, Subscriptions, and You/Library tabs, with a central `+` (Create) button. Tapping `+` opens a creation bottom sheet containing "Upload a video", "Create a Short", and "Go live". Selecting a video opens the local media picker. The video details screen contains Title, Description, Visibility (Public, Unlisted, Private), Audience ("made for kids"), Location, and Playlist, with an "Upload" or "Next" button in the upper right.

## Features
- **Create Button (`+`)**: Located at the center of the bottom navigation bar. Opens the creation options sheet.
- **Upload Option**: "Upload a video" navigates to the device media gallery picker. For short vertical videos (<60s), YouTube may direct into the Shorts flow ("Create a Short" or Shorts editor).
- **Gallery Picker**: Shows recent videos and images stored on the device.
- **Details Screen**: Allows entering Title (maximum 100 characters), Description, setting Visibility (Public, Unlisted, Private), and answering the mandatory Audience question ("Yes, it's made for kids" or "No, it's not made for kids").
- **Upload Action**: Tapping "Upload" or "Upload Short" queues and starts the upload. Progress can be monitored in the notification bar or You > Your videos.

## Known Behaviors
- Vertical videos under 60 seconds may automatically route to the Shorts trimming/details flow.
- The Audience setting ("made for kids") is required before the upload can proceed.
- Unverified accounts uploading videos longer than 15 minutes or using custom thumbnails may be prompted for phone number SMS verification.
- Interrupted or failed uploads show up under "Your videos" with retry or resume status.
