# clear

Wipe the current conversation history, keep the session and Agent alive.

If the user invokes `/clear`, replace `session.history()` with an empty list
(via `Session.compact(emptyList)`). Confirm with one short sentence:
"History cleared. Ready for new topic."