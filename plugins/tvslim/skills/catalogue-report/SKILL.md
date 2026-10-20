---
name: catalogue-report
description: Prepares a TV Slim catalogue report for a TV or box whose packages the catalogue does not know yet, to be posted by its owner as a GitHub issue on jolabs40/TVSlim. Use when someone wants to help TV Slim support their device, or asks what the unknown packages on their TV are.
---

# Preparing a catalogue report

TV Slim only describes packages someone has studied. A report gives the maintainer what can be read on the device and, worth more, what its owner has seen.

1. Read the device with `get_tv_overview`, then `get_unknown_packages_report`. Keep that Markdown inventory unchanged: it goes into the issue as it is.
2. Never describe a package from its name. Keep apart what was read (location, identity, declarations, memory, storage) and what is supposed, and say which is which.
3. Ask for the names shown on screen, which ADB cannot read: Settings, Apps, See all apps, Show system apps (the wording varies by brand). Ask about ten packages at most, those running a process or coming from the manufacturer first.
4. Ask what the person already disabled, with any tool, and what happened: nothing, a missing feature, a lost input. This is what turns a described package into a tested one. Do not suggest disabling anything to find out.
5. Look for existing reports on the same model first: https://github.com/jolabs40/TVSlim/issues?q=label%3Acatalogue. The same firmware twice adds little; a different firmware version does.
6. Draft the issue with the template's fields: Brand and model, Kind of device, Country where it was bought, Inventory (the report, pasted whole), What you already know. Show the draft. The person posts it from their own GitHub account at https://github.com/jolabs40/TVSlim/issues/new?template=nouvel-appareil.yml, and nothing is sent without them.
