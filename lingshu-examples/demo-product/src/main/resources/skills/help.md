# help

Show available commands and tool inventory.

If the user invokes `/help`, list:
- `/help` — this message
- `/clear` — wipe conversation history (keep session alive)
- `/compact` — force-trigger the compactor
- All registered tools (from `ToolRegistry.list()`): read_file, write_file, list_dir,
  bash_safe, time, calc, random, uuid, plus any Delegate sub-agents.

Format as a Markdown bullet list with one-line description per item.