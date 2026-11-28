"""Rules for the hand-written JSON that main keeps outside datagen (the GameTest data and assets).

Lib's datagen output does not go through rules: the port's own runData writes it in 1.21.1's formats
(port/generated, port/README.md "Resources"). These rules cover only what main writes by hand, which no 1.21.1
provider can produce.
"""

from __future__ import annotations

import json
from typing import Any

from framework import Transform

GAMETEST = "lib/neoforge/src/gametest/resources/"
_FLAT_TEXT = ("minecraft:custom_name", "minecraft:item_name")


def _ingredient(value: Any) -> Any:
    """26.1 writes an item ingredient as "#tag" or "id" (or a list of them); 1.21.1's Ingredient.CODEC wants objects."""
    if isinstance(value, str):
        return {"tag": value[1:]} if value.startswith("#") else {"item": value}
    if isinstance(value, list):
        return [_ingredient(v) for v in value]
    return value


def _recipe(text: str, rel: str) -> str:
    recipe = json.loads(text)

    def walk(node: Any) -> None:
        if isinstance(node, dict):
            for key, value in node.items():
                if key in ("ingredient", "binder"):
                    node[key] = _ingredient(value)
                elif key == "ingredients" and isinstance(value, list):
                    node[key] = [_ingredient(v) for v in value]
                elif key == "key" and isinstance(value, dict):
                    node[key] = {k: _ingredient(v) for k, v in value.items()}
                elif key in _FLAT_TEXT and not (isinstance(value, str) and value.startswith(('"', "{", "["))):
                    # 1.21.1 saves these components as text JSON inside a string (ComponentSerialization.FLAT_CODEC)
                    node[key] = json.dumps(value)
                else:
                    walk(value)
        elif isinstance(node, list):
            for value in node:
                walk(value)

    walk(recipe)
    return json.dumps(recipe, indent=2) + "\n"


def _glass_item_model(text: str, rel: str) -> str:
    # 26.1 draws item models translucent; 1.21.1 takes a block item's render type from the block's chunk layer unless
    # the model names one, so a glass shell would draw opaque.
    model = json.loads(text)
    textures = model.get("textures", {})
    if "render_type" in model or not any("glass" in str(t) for t in textures.values()):
        return text
    model["render_type"] = "minecraft:translucent"
    return json.dumps(model, indent=2) + "\n"


RULES = [
    Transform(_recipe, "26.1 ingredient strings -> 1.21.1 ingredient objects",
              files=(GAMETEST + "data/*/recipe/**.json",)),
    Transform(_glass_item_model, "translucent render type for glass item models",
              files=(GAMETEST + "assets/*/models/item/*.json",)),
]
