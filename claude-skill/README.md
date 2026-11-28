# Yaad skill for Claude Code

Lets Claude Code push a learning straight to your Yaad inbox ("add this learning", "yaad pe daal do").

Install: copy this folder to `~/.claude/skills/yaad/`, then copy `config.example.json` to
`config.json` and fill in your server URL and key. `config.json` holds a secret, so never commit it.

Check it: `python ~/.claude/skills/yaad/yaad_push.py --ping`
