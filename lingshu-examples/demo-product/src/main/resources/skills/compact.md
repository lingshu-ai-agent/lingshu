# compact

Force-trigger the history compactor on the next turn.

If the user invokes `/compact`, append a synthetic message instructing the
engine to compact, then run normally. The `TruncatingCompactor` will emit a
`Compacted` event with `approxTokensFreed > 0`. Surface that count in the
response so the user sees the savings.