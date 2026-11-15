# DiscordLogBridge

Paper/Purpur 1.21.x plugin that forwards new lines from `logs/latest.log` to a Discord webhook.

## Build
Requires Java 21 and Maven. Run `mvn package`; the jar is `target/discord-log-bridge-1.0.0.jar`.
GitHub Actions builds the plugin on every push. Download the jar from the workflow run's Artifacts section.

## Install
1. Upload the jar to the server's `plugins` folder and start once.
2. Open `plugins/DiscordLogBridge/config.yml`.
3. Set `webhook-url` to your Discord webhook URL. Keep it private.
4. Restart the server.

The plugin reads `logs/latest.log` incrementally and sends batches to Discord. Discord rate limits can delay high-volume logs. Final shutdown delivery is best-effort: a hard crash or host kill can prevent the last messages from being sent. Logs can contain player chat and IP addresses, so use a private Discord channel.
