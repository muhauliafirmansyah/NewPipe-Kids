# NewPipe Kids concept

## Goal

Create a child-friendly video app where parents choose the available content.
Children can browse and search independently within their approved library.

## Content approval

- Parents explicitly approve content for each child. Age-based labels and
  automated curation do not determine what is available.
- Parents can approve individual videos, channels, and playlists.
- Approving a channel automatically includes new uploads.
- Approving a playlist automatically approves its videos, including videos
  added later.
- Removing a playlist or a video from it removes the resulting approval, but
  the video remains available if another approval still covers it.
- Refresh approved channels and playlists when the app opens and periodically
  while it is in use. If a refresh fails, use the last known library and
  continue enforcing local approval removals and hidden content. After the
  session, the parent can update the configuration or restore internet access.
- An explicitly hidden video stays unavailable even if an approved channel
  or playlist also includes it.
- The parent interface clearly explains the scope of each approval.

## Child profiles

- Each child has a separate profile and approved library.
- Parents can assign the same content to more than one profile.
- Each profile has its own watch history and favorites.
- Children can switch profiles by default. Parents can optionally require
  parent authorization to switch profiles.
- Disabled profiles do not appear in the child view, but remain available in
  Parent mode for re-enabling.
- Each profile keeps its own remaining session time. Switching profiles and
  returning resumes the existing session rather than resetting it.

## Child experience

- Children browse approved content through a simple visual interface.
- Children can search, but results come only from their approved library.
- Related videos, recommendations, playlists, and autoplay stay within the
  active profile's approved library.
- Videos outside the approved library cannot open through links or other
  navigation paths.
- If the app detects that the currently playing video is no longer covered by
  any approval, it stops playback. Playback continues if another approval
  still covers the video.
- Autoplay is enabled by default, and children can turn it on or off.
- Autoplay prefers approved videos the child has not watched. Once every
  approved video has been watched, it chooses the video whose most recent
  viewing was longest ago. A video counts as watched when the child has played
  at least 90% of it.
- When autoplay is enabled, playlist playback follows the playlist order;
  after the last video, it continues according to the general autoplay rules.
  When autoplay is disabled, playback stops after each video, and a Next button
  lets the child play the next playlist video manually.
- If a video is unavailable, show a simple message and let the child choose
  another approved video. Autoplay skips unavailable videos.

## Parent mode

- Parent mode is protected by a parent-controlled PIN.
- Parents can add, rename, remove, disable, and re-enable child profiles.
- Parents can search, browse, and review videos, channels, and playlists before
  approving them for selected child profiles.
- Parents can assign and remove approved content for each profile.
- Parents can browse approved videos, channels, and playlists, see the scope
  of each approval, and remove approvals.
- The approved content list is the starting point for content management and
  can be empty. Parents can open an approved item, temporarily disable or hide
  it, re-enable it, or remove its approval.
- Disabled or hidden content stays in the parent's list but is unavailable to
  the child. An Add content action opens a screen where parents can search or
  browse videos, channels, and playlists, open them, and approve them.
- Parents can set a per-session time limit from 1 minute to 2 hours and reset
  a session's limit. There is no daily limit for now; parents decide whether to
  start another session.
- The session timer counts only while the child is actively using the app,
  including browsing and playback, and pauses when the app is backgrounded.
- A countdown warns the child before the session ends. Playback stops when the
  limit is reached, and only a parent can start another session.
