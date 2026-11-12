# NewPipe Kids: user flow

This flow shows the main paths for children and parents. The rules behind it
are described in [CONCEPT.md](CONCEPT.md).

## First-time setup

```mermaid
flowchart TD
    A[Open app] --> B[Parent creates a PIN]
    B --> C[Create a child profile]
    C --> D[Set a session time limit]
    D --> E[Open approved content list - initially empty]
    E --> L[Add content: search or browse]
    L --> F[Open a video, channel, or playlist]
    F --> G[Approve for one or more profiles]
    G --> H[Choose a child profile]
    H --> I[Child home]
```

When a parent approves a channel or playlist, the app explains that future
uploads or additions will also be available. The parent can add more content
and profiles later in Parent mode.

## Child: find and watch a video

```mermaid
flowchart TD
    A[Choose profile] --> B[Child home]
    B --> C[Browse approved library]
    B --> D[Search approved library]
    C --> E[Choose video, channel, or playlist]
    D --> E
    E -->|Video| F[Play video]
    E -->|Channel or playlist| L[Browse its approved videos]
    L --> F
    F --> G{Video ends}
    G -->|Autoplay off| H[Stop and show available controls]
    G -->|Autoplay on| I{Playing a playlist?}
    I -->|More playlist videos| J[Play next playlist video]
    I -->|No playlist or playlist finished| K[Choose an approved video]
    J --> F
    K --> F
    H -->|Next button in a playlist| J
    H -->|Choose another video| B
```

- The child can turn autoplay on or off.
- Search can match approved videos, channels, and playlists. A child can also
  view favorites; hidden or disabled content stays out of those views.
- Outside a playlist, autoplay prefers unwatched approved videos. Once all
  approved videos have been watched, it chooses the one whose most recent
  viewing was longest ago. A video counts as watched after 90% has played.
- If a video is unavailable, the app shows a simple message and the child can
  choose another approved video. Autoplay skips unavailable videos.
- Search, related content, playlist playback, and links only open content
  approved for the active profile.

## Child: switch profiles

```mermaid
flowchart LR
    A[Child home] --> B[Switch profile]
    B --> C{Parent lock enabled?}
    C -->|No| D[Choose another profile]
    C -->|Yes| E[Parent enters PIN]
    E --> D
    D --> F[Open selected profile]
```

Each profile keeps its own session time. Returning to a profile resumes its
existing session; switching profiles does not reset it. Disabled profiles are
hidden from children and can be re-enabled in Parent mode.

## Session time limit

```mermaid
flowchart TD
    A[Child uses app] --> B[Session time counts down]
    B --> C[Countdown warning]
    C --> D[Time runs out]
    D --> E[Stop playback and show session ended]
    E --> F[Parent enters PIN]
    F --> G[Parent resets limit and starts another session]
    G --> A
```

Time counts while the child actively uses the app, including browsing and
playback, and pauses when the app is backgrounded. Only a parent can start
another session after the limit is reached. Parents can set the session limit
from 1 minute to 2 hours. There is no daily limit for now; parents decide
whether to start another session. The warning appears for the last 10% of a
session, with a minimum of 10 seconds and a maximum of one minute.

## Parent: manage content and settings

```mermaid
flowchart TD
    A[Open Parent mode] --> B[Enter parent PIN]
    B --> C[Parent home]
    C --> G[View approved content - may be empty]
    G --> L[Open approved video, channel, or playlist]
    L --> M[Disable or hide content]
    L --> N[Re-enable hidden content]
    L --> H[Remove an approval]
    G --> D[Add content]
    D --> O[Search and browse videos, channels, and playlists]
    O --> E[Open content to review]
    E --> F[Select child profiles and approve]
    C --> I[Manage child profiles]
    I --> J[Add, rename, remove, disable, or enable profiles]
    I --> P[Change session limits or reset a session]
    F --> C
    H --> C
    M --> G
    N --> G
    J --> C
    P --> C
    C --> K[Return to child experience]
```

In Add content, parents choose YouTube, PeerTube, or media.ccc.de. For
PeerTube, they can choose the server to search. The parent reviews an item and
selects the profiles that should receive it.

Removing an approval removes access only when no other approval covers the
video. If the app detects that a currently playing video is no longer covered,
it stops playback. Approved channels and playlists refresh when the app opens
and periodically while in use; if a refresh fails, the last known library is
used.

Disabling or hiding an approval keeps it in the parent's approved content list
but makes that content unavailable to the child until it is re-enabled.
An explicitly hidden video stays unavailable even if an approved channel or
playlist also includes it. When refresh is unavailable, the app still applies
local approval removals and hidden content to the last known library. After the
session, the parent can update the configuration or restore internet access.
