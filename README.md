# ColumnRay

一個 2.5D raycasting 引擎的起點，用純 Java（Swing）寫，不需要任何外部套件。地圖是 2D 俯視圖，每一欄射一條光線。所有高度都用同一條投影公式算出來。示範場景是學校 F1 的一部分：教室、走廊、中庭、水池、樓梯。

## 執行

需要 JDK 21 以上（開發時用 JDK 26）。

```bash
./run.sh                                   # 開視窗，載入 maps/school.json
./run.sh maps/school.json --shot a.png     # 不開視窗，輸出一張截圖（出生點）
./run.sh --shot a.png 16 21.6 -100 5       # 指定位置 x y、方向角、仰角（度）
./run.sh --bench                           # 原地轉一圈，量渲染時間
```

操作：WASD 移動、拖曳滑鼠或方向鍵轉頭／抬頭（±30°）、Q / E 轉向、Space 跳、C 蹲、Shift 跑、M 開關小地圖、Esc 離開。

## 檔案

| 檔案 | 內容 |
|---|---|
| `src/engine/Renderer.java` | 相機、投影、DDA 走格子、interval 填充、區域邊界、打光 |
| `src/engine/Geometry.java` | 射線對線段、圓、凸多邊形的交點；碰撞用的距離函式 |
| `src/engine/World.java` | 區域和形狀資料、JSON 載入、加速格子、位置查詢 |
| `src/engine/Materials.java` | 程序貼圖（之後換成圖片貼圖和 normal map） |
| `src/engine/Main.java` | 視窗、輸入、玩家物理（台階、跳、蹲）、HUD、小地圖 |
| `src/engine/Json.java` | 最小的 JSON 解析器（允許 `//` 註解） |
| `maps/school.json` | 示範地圖 |

## 演算法對照

1. **相機**：`r = dir + plane * camX`，`PL = 0.66`，`F = (W/2)/PL`。r 刻意不 normalize，所以交點參數 t 就是垂直距離，不會有魚眼。→ `Renderer.Column.render`
2. **投影**：`rowZ(z, t) = hz - (z - eye) * F / t`，`hz = H/2 + pitch`（y-shearing）。→ `rowZ`
3. **交點**：線段、圓（進入 t1、離開 t2）、凸多邊形（所有邊交點取最小和最大的 t）。t1 是負的就代表站在形狀範圍內。→ `Geometry`
4. **Interval 填充**：每一欄記錄還空著的行，東西由近到遠畫，只填空的地方，填滿就停。→ `paint`
5. **每個物件畫三部分**：側面、頂面（眼睛比頂高時）、底面（眼睛比底低時）。→ `drawHit`
6. **地板和天花板**：從行反推距離 `t = (eye - z) * F / (y - hz)`，再用 `pos + r * t` 找出世界座標來貼圖。天花板會裁切比它高的物件（`min(h, ceil)`）。→ `surfaces`、`flat`
7. **區域邊界（portal）**：光線跨進下一區時比較兩邊高度。下一區天花板比較低就畫門楣，地板比較高就畫台階立面。露天區域（中庭）沒有天花板，看出去就是天空。→ `cross`
8. **打光**：線段的法線是邊向量轉 90°，圓柱的法線是（交點 − 圓心）/ 半徑，再乘上距離霧。→ `lambert`、`fog`

### 物件和區域怎麼排順序

DDA 一格一格往前走，把這格裡的形狀加進待處理清單。處理到這格的出口距離 `tout` 時，所有 `t1 ≤ tout` 的物件順序都已經確定，因為之後的格子才發現的形狀，進入點一定更遠。這些物件和「跨進下一個區域」依距離交錯處理。兩次事件之間，畫目前區域那一段地板和天花板。所以物件前面的地板會先畫，後面的地板會被物件擋住。

## 地圖格式

請看 `maps/school.json` 開頭的註解。重點：

- **區域**是凸多邊形，帶 `floor` 和 `ceil`。`ceil` 省略或寫 `null` 就是露天。`top` 是從露天看進來時，開口上方那段牆的頂。
- **形狀**有 `wall`（線段）、`circle`、`box`、`poly`、`ngon`，每個都帶 `z0`（底）和 `h`（頂）。`maxDist` 設定超過多遠就不畫。
- **array** 會把 `items` 依 `step` 平移複製 `count` 次（桌椅、柱列）。
- 多邊形必須是凸的，載入時會檢查。L 形要切成好幾塊。

## 還沒做的

- **樓層**：還是只有一層。要加上層的篩選：只測畫面開口看得到的樓層，F2–F5 共用標準層，樓板有厚度，中庭和樓梯井是樓板上的洞。
- **現代光影**：CPU 逐像素上色要改成輸出 G-buffer（深度、法線、材質、UV），交給 GPU 做光照、陰影、反射、bloom、tone mapping。
- **水面倒影**：把眼睛高度鏡像到水面下，再走一次同一條光線，加上漣漪 normal map 和 Fresnel。
- **地圖外**：光線走出地圖後，目前不會再走回來。
