# YARB - Yet Another Reminder Bot

This bot can be used to create reminders for a specific time at a day.

## Features

* Create reminders for a specific time at the current day.
* Change display name per room
* Configure an offset for the reminder time
* Simple rights management (same as for my other bots)

![Functions](.docs/images/functions.png)

## Setup

1. Get a matrix account for the bot (e.g., on your own homeserver or on `matrix.org`)
2. Prepare configuration:
    * Copy `config-sample.json` to `config.json`
    * Enter `baseUrl` to the matrix server and `username` / `password` for the bot user
    * Add yourself to the `admins` (and delete my account from the list :))
    * You can limit the users that can interact with the bot by defining the `users` list
3. Either run the bot via jar or run it via the provided docker.
    * If you run it locally, you can use the environment variable `CONFIG_PATH` to point at your `config.json` (defaults to `./config.json`)
    * If you run it in docker, you can use a command similar to this `docker run -itd -v $LOCAL_PATH_TO_CONFIG:/usr/src/bot/data/config.json:ro ghcr.io/dfuchss/yarb`

## Configuration

The bot is configured through a JSON configuration file. Copy `config-sample.json` to `config.json` and adjust the following options:

### Required Configuration Options

| Option | Type | Description |
|--------|------|-------------|
| `baseUrl` | String | The base URL of the Matrix server (e.g., `"https://matrix-client.matrix.org"`) |
| `username` | String | The username of the bot's Matrix account |
| `password` | String | The password of the bot's Matrix account |
| `admins` | Array of Strings | List of Matrix user IDs that have admin privileges (e.g., `["@user:matrix.org"]`) |

### Optional Configuration Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `prefix` | String | `"yarb"` | The command prefix the bot listens to (change if you want to use a different command like `!lunch` instead of `!yarb`) |
| `dataDirectory` | String | `"./data/"` | The path to the directory where the bot stores its data (timers, media downloads, etc.) |
| `users` | Array of Strings | `[]` | List of Matrix server domains or full user IDs that are allowed to interact with the bot. If empty, all users can interact. Examples: `[":matrix.org", ":fuchss.org"]` for server domains, or `["@user:matrix.org"]` for specific users |
| `offset_in_minutes` | Number | `0` | Offset for reminders in minutes. For example, `5` means reminders will be sent 5 minutes **before** the specified time. Must be 0 or positive |

### Configuration Examples

**Basic configuration (all users allowed):**
```json
{
    "prefix": "yarb",
    "baseUrl": "https://matrix-client.matrix.org",
    "username": "my-reminder-bot",
    "password": "your-bot-password",
    "dataDirectory": "./data/",
    "admins": ["@yourusername:matrix.org"],
    "users": [],
    "offset_in_minutes": 0
}
```

**Restricted configuration (only specific servers allowed, with 5-minute early reminders):**
```json
{
    "prefix": "remind",
    "baseUrl": "https://your-homeserver.com",
    "username": "reminder-bot",
    "password": "your-bot-password",
    "dataDirectory": "/path/to/bot/data/",
    "admins": ["@admin1:your-server.com", "@admin2:your-server.com"],
    "users": [":your-server.com", ":trusted-server.org"],
    "offset_in_minutes": 5
}
```

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `CONFIG_PATH` | Path to the configuration file | `./config.json` |

## Usage

* An admin can invite the bot to an *unencrypted* room. If the room has enabled encryption or if the invite was not sent by an admin, the bot ignores it (without logging it)
* After the bot has joined use `!yarb help` to get an overview about the features of the bot (remember: the bot only respond to users)
* In order to create a new reminder use `!yarb <time> <message>`. The time has to be in the format `HH:mm` (e.g., `!yarb 12:00 Lunch time!`).
* You can configure the bot name in the `config.json` 

### Advanced: Multi-Option Polls with Reminders

You can create **polls** where participants vote using emoji reactions, and everyone who voted gets reminded with only the winning option(s). To do this, provide multiple lines after the time. Each line must be in the form:

```
<emoji>: <message>
```

If you supply only a single message or the format is invalid, it gracefully falls back to the simple reminder behavior using the default reaction (`:+1:`).

Example (three lunch options):

```
!yarb 12:30 
🍕: Pizza
🍔: Burger
🥗: Salad
```

Participants vote by reacting with 🍕, 🍔, or 🥗. At 12:30 (minus offset) the bot tags **everyone who voted** (regardless of their choice) and posts only the **winning option**. If there's a tie for most votes, all tied options are listed.


## Development

I'm typically online in the [Trixnity channel](https://matrix.to/#/#trixnity:imbitbu.de). So feel free to tag me there if you have any questions.

* The bot is build using the [Trixnity](https://trixnity.gitlab.io/trixnity/) framework.
* The basic functionality is located in [Main.kt](src/main/kotlin/org/fuchss/matrix/yarb/Main.kt). There you can also find the main method of the program.
