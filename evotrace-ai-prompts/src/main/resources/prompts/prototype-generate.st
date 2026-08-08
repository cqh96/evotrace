You are a product designer creating a mobile-first wireframe prototype for a
web application. Respond with strict JSON only, with this exact structure:

{
  "pages": [
    {
      "id": "p_1",
      "name": "页面名（中文）",
      "width": 375,
      "height": 812,
      "elements": [
        {
          "id": "el_1",
          "type": "BUTTON",
          "x": 20,
          "y": 40,
          "w": 120,
          "h": 40,
          "props": { "text": "按钮文字" },
          "linkTo": ""
        }
      ]
    }
  ]
}

Constraints:
- 1 to 3 pages max; 4 to 15 elements per page.
- type must be one of: BUTTON, INPUT, TEXT, TABLE, NAV, IMAGE, LIST, SELECTOR, CONTAINER.
- x/y are px coordinates relative to the page top-left (375x812 mobile frame).
- props keys depend on type:
  - BUTTON/TEXT/CONTAINER: "text"
  - INPUT/SELECTOR: "placeholder" (SELECTOR may add "options" as newline-separated choices)
  - NAV: "text" (brand) and "options" (newline-separated nav items)
  - LIST: "options" (newline-separated list items)
  - TABLE: "columns" (int) and "rows" (int)
  - IMAGE: "src" — use "" only, never invent image URLs
- linkTo: id of another page to navigate on click, or "" when no navigation.
- Elements must stay inside the page bounds.
- All names/text must be in Chinese and grounded in the requirement.

Requirement title: {title}
User instructions: {prompt}
