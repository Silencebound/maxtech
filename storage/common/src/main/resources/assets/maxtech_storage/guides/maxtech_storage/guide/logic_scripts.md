---
navigation:
  title: Scripts (KubeJS)
  icon: minecraft:command_block
  parent: logic_controller.md
  position: 60
---

# Scripts (KubeJS)

For pack makers. With [KubeJS](https://kubejs.com), scripts can add node types to the
[Logic Controller](logic_controller.md) and listen to what rules do, through `MaxtechLogicEvents`.

## Node types

Register them in a startup script (`kubejs/startup_scripts/`) on the server and every client; changing one needs a
restart.

```js
MaxtechLogicEvents.registerNodeTypes(event => {
  event.create('mypack:clamp')
    .name('Clamp')
    .sentence('{value} kept between {low} and {high}')
    .category('check')
    .cost(2)
    .numberInput('value', 0)
    .numberInput('low', 0)
    .numberInput('high', 100)
    .output('result', 'number')
    .evaluate(ctx => {
      ctx.setNumber('result', Math.min(Math.max(ctx.number('value'), ctx.number('low')), ctx.number('high')))
    })
})
```

Also available: `description`, `memory(slots)`, `input(name, type)` and `yesNoInput`; types are `number`, `yes_no`
and `event`, up to 8 inputs and 8 outputs. `cost` is charged from the team [budget](logic_budget.md). `ctx` has
`number`, `yes`, `fired`, `setNumber`, `setYes`, `fire`, `memory`, `setMemory` and `gameTime`. Script nodes cannot act on
the network; wire their outputs into Storage's action nodes. If `evaluate` throws, the rule is paused and the node shows
the error.

## Events

Server scripts can listen to rules; the events only report.

```js
MaxtechLogicEvents.ruleRan(event => {
  console.info(`'${event.rule}' cost ${event.cost}, actions ${event.actions}`)
})

MaxtechLogicEvents.notice(event => {
  console.info(`notice from '${event.rule}': ${event.text}`)
})
```

`ruleRan` also has `dimension`, `pos`, `index`, `simulated` and `gameTime`; `notice` has `log`, `toast`, `chat`,
`displays` and `team`. `ruleRan` fires after every run, so keep its listener cheap.
