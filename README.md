# FluxerSRV

**FluxerSRV** is a custom, lightweight Minecraft plugin designed to bridge your Minecraft server with the [Fluxer.app](https://fluxer.app) platform. 

Built from the ground up as a standalone alternative to bulky Discord bridge plugins, FluxerSRV connects directly to the Fluxer WebSocket Gateway and REST API to provide seamless, real-time communication between your players and your Fluxer community.

## ✨ Features

* **Bidirectional Chat Bridge**: Messages sent in-game appear in your Fluxer `global` channel, and messages sent in Fluxer instantly broadcast to players in-game.
* **Per-Player Chat Webhooks (optional)**: Post in-game chat through a Fluxer webhook so every message appears under the player's own name and avatar instead of the bot's. Enable it under `webhooks:` in `config.yml` (the bot needs `Manage Webhooks`, or paste an existing webhook URL).
* **Custom In-Game Format**: Change how Fluxer messages look to players via `ingame.format` (`&` colors, `&#RRGGBB` hex, `%username%`, `%display_name%`, `%nickname%`, `%message%`).
* **Live Player Count Status (optional)**: Show `online/max players online` as the bot's Fluxer custom status, refreshed every N seconds. Configure under `status:` in `config.yml` (`enabled`, `update-interval-seconds`, `format`, `presence`).
* **Reload Command**: `/fluxer reload` (permission `fluxersrv.admin`) re-reads `config.yml` without a restart. Bot token changes still need a restart.
* **Replies & Mentions**: Fluxer replies show who/what was replied to in Minecraft chat (`ingame.reply-format`, with an optional multi-line layout), and `<@id>` pings, role mentions, channel mentions and custom emoji are shown as `@Name`, `#channel` and `:emoji:` (`ingame.mentions.*`).
* **Advancements**: Player advancements, goals and challenges are posted to the global channel (`formats.advancement`, `formats.goal`, `formats.challenge`; toggle with `announce-advancements`). Works on 1.12+ (advancements) and 1.8-1.11 (achievements).
* **Clickable Media & Links**: Images, gifs, videos and files sent on Fluxer show as just their file name in Minecraft, and links show as just their name (the `[text]` of a `[text](url)` link, or the shortened URL). Clicking opens the attachment or link; there is no extra hover text. Style them with `ingame.media-placeholder`, `ingame.file-placeholder` and `ingame.link-placeholder` (`%name%` is the name). Needs a Spigot/Paper-based server.
* **InteractiveChat Support**: `[item]`, `[inv]`, `[ender]` and your own placeholders are posted to Fluxer as Fluxer markdown (an inline tag plus a code block with enchantments, lore, durability, the inventory or ender chest contents, and what is inside shulker boxes and bundles), respecting InteractiveChat's `interactivechat.module.*` permissions when it is installed. Optional `!playerlist`, `!item`, `!inv`, `!ender` text commands. Configure under `interactivechat:`.
* **Live Console Streaming**: All server console logs are securely streamed directly to a designated `console` channel on Fluxer.
* **Remote Console Execution**: Server administrators can type commands directly into the Fluxer `console` channel, and the plugin will execute them as the server console.
* **Join, Quit, and Death Events**: Automatically broadcasts player logins, logouts, and death messages to the Fluxer community.
* **Lightweight & Fast**: No bloat, no unnecessary dependencies. Built purely for the Fluxer ecosystem.

## 🚀 Installation & Setup

1. **Download the Plugin**: Place the `FluxerSRV.jar` file into your Minecraft server's `plugins/` folder.
2. **Start the Server**: Run your server once to let the plugin generate its default `config.yml`.
3. **Get your Bot Token**:
   * Go to your **User Settings** in the Fluxer app.
   * Navigate to **Applications** -> **Create a New Application**.
   * Copy the **Bot Token**.
   * Use the OAuth2/Bot Invite URL generator to invite the bot to your server with `View Channels` and `Send Messages` permissions.
4. **Configure the Plugin**:
   * Open `plugins/FluxerSRV/config.yml`.
   * Paste your Fluxer bot token into the `botToken` field.
   * Set your `global` and `console` channel IDs.
5. **Restart the Server**: Restart your Minecraft server. The bot will automatically come online and bridge your server!

## ⚙️ Configuration
The `config.yml` file allows you to customize the bot token, channel bindings, and format strings for every type of message (chat, joins, quits, server startup, and server shutdown).

## 📄 License
This project is licensed under the GNU General Public License v3.0 (GPLv3). See the `LICENSE` file for details. This explicitly prevents anyone from distributing closed-source or commercial forks of your plugin without your permission!
