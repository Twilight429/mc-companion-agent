---
name: social_interaction
description: Invite the player to a consented harmless high-five gesture, jump or spin. Player accepts with H or declines with Shift+H. Suitable for cooperative building celebrations; never attack or force the player's movement. 击掌、跳跃、转圈、完工庆祝、玩家回应。
---

# A small shared celebration

Read `numen.api.help("numen.social")`. Offer only when safe and contextually appropriate:

```lua
numen.social.offer("high_five", "房子搭好了，击个掌？")
```

Kinds are `high_five`, `jump`, `spin`. A short invitation lasts 15 seconds; the real owner
must be in the same dimension and within three blocks, then press H. Shift+H declines.
The C cooperation screen can also originate an invitation. No forged consent, repeated offers
or automatic motion before an answer. There is a 30-second invitation cooldown.

The accepted action uses Numen's native short task slot; survival retains priority and the
underlying long job can resume. Actual failure/interruption returns via `social_interaction_result`.
High-five is a non-damaging hand swing toward the owner, not an attack or guaranteed custom
model animation. Jump succeeds only after observed rise and landing; spin verifies actual turn.
The owner's body is never forcibly moved, made to jump or rotated. They can join voluntarily.

Do not celebrate before a building is actually verified. Do not invite during danger, critical
health or difficult terrain. Refusal/expiry is ordinary: continue the original work, no pressure.
If Yes Steve Model is installed, load its own `ysm_look` skill to inspect actual model options.
Do not guess emote IDs from a model filename or claim an unavailable animation worked.
