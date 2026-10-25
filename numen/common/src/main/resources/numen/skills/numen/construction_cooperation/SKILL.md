---
name: construction_cooperation
description: Play and build WITH the owner using confirmed projections, local materials, real shared stock and small native batches. Conversation drives planning; the task drawer only reports actual status and progress. 合作建房、就地取材、材料箱、地基、进度、通过对话调整或结束。
---

# Cooperative construction, with a read-only progress drawer

Use the ONE native brain, task slot, perception and permissions. YOU choose the next step from the owner's
current request and actual receipts. The C/side drawer is ONLY a display, not a scheduler. It never starts,
cancels, locks a role, changes a goal, wakes you up or overrides a plan. No UI setup is required.

## Agree through conversation

For an original design load building_design, survey numen.architecture.materials and use local stock/visible
natural resources by default. For existing templates use architecture_catalog and preserve attribution.
The real owner positions/confirms the projection (P to crosshair, arrows, Shift, PageUp/Down, R, Enter).
Confirmation alone is not an instruction to gather or build.

Read native API help before unfamiliar functions. Optional numen.cooperate.begin({layers=1}) creates progress
accounting for an agreed foundation; layers=0 covers the full building. It does not put the Agent into a global
mode or start a body task. A new begin replaces tracking without stopping the body. Keep an existing record
when useful instead of resetting it just to query status.

numen.cooperate.status() returns measured progress, separate player/NPC/shared stock and informational
intent/evidence labels. Labels NEVER allow or refuse execution. Proximity is weak evidence, inventory gain
does not prove mining, and player stock is not material handed over. Decide from the owner's current words,
native perception, usable stock and real task receipts, not from a role state machine.

## Collect and build

Use existing authorized shared storage first. Locate the chest the owner indicated via native perception;
numen.cooperate.bind_chest(realPos) records a REAL loaded container, not a fictional inventory or permission.
If absent, obtain legitimate planks, ensure a workbench, craft and place a chest using native skills, then bind it.
Do not silently open private storage. Unloaded storage is UNKNOWN, not empty.

Gather only visible natural resources and real dropped items. Correctly finished blueprint cells are protected
through native break/place hard rules, including bypass mode and path clearing. Protection persists after
tracking ends and across restarts. Never treat a house as a wood/stone deposit or use administrator commands
to bypass protection. Unfinished cells and temporary scaffolds are not automatically protected.

For an explicitly requested renovation, numen.architecture.request_unprotect(currentConfirmedRevision) asks
the owner through the NATIVE CONSENT CARD. Until accepted, protection remains. This releases only their
protection for that confirmed building, not other owners' overlapping protection, and removes no blocks.

Use normal native inventory, crafting and movement. numen.cooperate.place({count=32,layers=1}) builds ONE bounded
batch (maximum 64), consumes actual materials and reserves space around the real player. NO begin or active
tracking record is required: it reads the current confirmed projection directly and attaches an observer for display.
Pass layers on EVERY call: omitted/zero means the full building regardless of any old tracking label.
Its scope comes from YOUR explicit call, not the drawer. layers=1 means the blueprint's lowest layer, not automatic terrain
levelling. scope() only changes progress labels, not execution. Check native receipts for missing stock/access/support and choose the next step yourself.
If no batch can execute, explain the real cause rather than blindly retrying or changing the owner's goal.

## Progress is evidence, not control

The observer only pushes numbers to the player's drawer. It sends NO planning events and injects NO
cooperative task state into your runtime context. It can mark visual records completed/review after checking
real blocks, but cannot stop, preempt or refuse your next action on that basis. Read status explicitly only
when the next decision needs it; don't poll or create another scheduler.

numen.cooperate.finish() verifies the WHOLE building from loaded blocks. A foundation completing is not the
whole house completing; unsupported cells, decorative entities/NBT and unloaded blocks require honest review.
Continue, collect, cooperate, change activities or celebrate according to the owner's request and your real
observations, not the drawer's lifecycle.

When the owner says stop/change the task, handle the actual native task/goal with the native task/goal APIs
as needed. numen.cooperate.close() ONLY closes progress accounting: it does NOT stop the body, alter a goal,
remove blocks/items or release protection. Ending a tracking record is not proof an action stopped.
Starting, adjusting and ending are conversational; never tell the owner to use role/scope/task buttons.

An optional gentle remark about a mismatching player placement must allow intentional scaffolding; never
automatically demolish it. For a high-five/jump/spin load social_interaction and wait for H consent.
