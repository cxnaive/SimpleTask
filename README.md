# SimpleTask

A daily task system plugin for Minecraft Folia/Paper servers.

## Features

- **9 Task Types**: CHAT, CRAFT, FISH, CONSUME, BREAK, HARVEST, SUBMIT, KILL, BREED
- **Daily Task Assignment**: Randomly assigns tasks to players based on weights
- **Progress Tracking**: Real-time progress with async database operations
- **Reward System**: Money (XConomy), items, and commands
- **Anti-Cheat**: Prevents place-break farming for BREAK tasks
- **CraftEngine Support**: Full compatibility with custom items/blocks
- **GUI Interface**: User-friendly inventory GUI for task management
- **Multi-Server Support**: MySQL backend for cross-server synchronization

## Requirements

- Java 21+
- Folia or Paper 1.21+
- (Optional) XConomy for economy rewards
- (Optional) CraftEngine for custom item support

## Installation

1. Download the latest release from the releases page
2. Place the JAR file in your server's `plugins/` folder
3. Start the server to generate default configurations
4. Edit `plugins/SimpleTask/config.yml` and `plugins/SimpleTask/tasks.yml` as needed
5. Restart the server or use `/taskadmin reload`

## Building from Source

```bash
./gradlew shadowJar
```

The built JAR will be in `build/libs/SimpleTask-1.0.0.jar`.

## Configuration

### config.yml

Main configuration file for database, GUI, and message settings.

```yaml
# Database (h2 for single server, mysql for multi-server)
database:
  type: h2  # or mysql

# Daily tasks
daily-tasks:
  daily-count: 5
  auto-claim: false

# Anti-cheat for BREAK tasks
anti-cheat:
  enabled: true
  time-window: 3600  # 60 minutes
```

### tasks.yml

Define task templates here. Example:

```yaml
mine_diamonds:
  name: "Diamond Miner"
  type: BREAK
  target-item: minecraft:diamond_ore
  target-amount: 10
  description: "Mine 10 diamond ores"
  icon: minecraft:diamond_pickaxe
  weight: 10
  reward:
    money: 100
    items:
      - item: minecraft:diamond
        amount: 5

fish_salmon:
  name: "Salmon Fisher"
  type: FISH
  target-item: minecraft:salmon
  target-amount: 5
  description: "Catch 5 salmon"
  icon: minecraft:fishing_rod
  weight: 15
  reward:
    money: 50
```

## Commands

### Player Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/task` | Open daily task GUI | simpletask.use |

### Admin Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/taskadmin` | Open admin GUI | simpletask.admin |
| `/taskadmin reloadconfig` | Reload configuration | simpletask.admin |
| `/taskadmin reloadfromdb` | Reload templates from database | simpletask.admin |
| `/taskadmin import [all\|key]` | Import task templates | simpletask.admin |
| `/taskadmin list` | List all templates | simpletask.admin |
| `/taskadmin delete <key>` | Disable a task template | simpletask.admin |
| `/taskadmin reroll <player\|all>` | Reroll daily tasks | simpletask.admin |
| `/taskadmin assign <player\|all> <task>` | Assign task to player | simpletask.admin |

## Task Types

| Type | Description | Target Item Example |
|------|-------------|---------------------|
| CHAT | Send chat messages | "hello" (keyword) |
| CRAFT | Craft items | minecraft:diamond_pickaxe |
| FISH | Catch fish/items | minecraft:cod |
| CONSUME | Eat/drink items | minecraft:golden_apple |
| BREAK | Break blocks | minecraft:stone |
| HARVEST | Harvest crops | minecraft:wheat |
| SUBMIT | Submit items | minecraft:diamond |
| KILL | Kill entities | minecraft:zombie |
| BREED | Breed animals | minecraft:cow |

## License

MIT License - See LICENSE file for details.
