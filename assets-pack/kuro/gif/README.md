# Kuro GIF Packs

Map ระหว่าง 862 WAV (17 events) กับ GIF — จัด GIF ลง folder ตาม emotion/event

**กฎ:** ตอน Android trigger reaction → pick WAV จาก event pool + **สุ่ม GIF จาก matching folder** ส่งทั้งคู่ไปหุ่น

**Idle (ไม่มี WAV):** firmware ใช้ `data/idle.gif` + `data/idles/` ของตัวเอง — Android **ไม่ส่ง GIF** ตอนไม่มีเสียง (ตามที่ user ระบุ — กันรบกวนตอนขับรถ)

---

## Folder map

| Folder | จับคู่กับ event WAV | จำนวน WAV | แนะนำ GIF จาก `gif_crushed/` |
|---|---|---|---|
| **HAPPY** | IDLE_HAPPY | 60 | happy_2, smile, hehe, leu_leu, star, mochi, mochidoo, love, love2 |
| **EXCITED** | IDLE_EXCITED | 60 | speed, speed_ex, xi_khoi, xi_lua, samurai, aot, star |
| **SAD** | IDLE_SAD | 40 | cry, cry2, scared |
| **ANGRY** | IDLE_ANGRY | 30 | angry, angry2, angry3, gian_du, devil, devil_2 |
| **BORED** | IDLE_BORED | 60 | hat_xi, hat_xi_2, chong_mat, cuoi_khinh_bi, noname |
| **HUNGRY** | IDLE_HUNGRY | 40 | sushi, mochi, mochidoo |
| **WELCOME** | CAN_DOOR_OPEN | 120 | smile, hehe, mochi, love, star, leu_leu |
| **READY** | CAN_ENGINE_START | 120 | speed, speed_ex, samurai, aot, star, music |
| **STARTLED** | CAN_IMU_BUMP + harsh_brake_spam | 100 | scared, ngac_nhien, angry (slight) |
| **STUCK** | HYP_STUCK_TRAFFIC | 80 | chong_mat, hat_xi, hat_xi_2, den_giao_thong, cuoi_khinh_bi |
| **HOT** | ambient_hot | 25 | chong_mat, xi_khoi (steam), xi_lua |
| **COLD** | ambient_cold | 25 | scared, blink (ตัวสั่น), nheo_mat (หรี่ตา) |
| **WORRIED** | CAN_MIL_ON | 25 | cry, scared, devil (light) |
| **PANIC** | CAN_OVERHEATING | 12 | devil, devil_2, scared, gian_du, xi_khoi |
| **ADVENTURE** | wanderer_long_drive | 25 | speed, samurai, aot, sakura, neon, music, rain |

**Skipped (ปล่อย firmware idle):** IDLE_SLEEPY — ตอน Kuro ง่วง = ไม่ส่งอะไรไปหุ่น = firmware เล่น idle.gif/idles/ ของตัวเอง

---

## แนะนำจำนวน GIF ต่อ folder

| Pool size | จำนวน GIF |
|---|---|
| Folder ที่ใช้บ่อย (WELCOME, READY, STUCK, HAPPY, EXCITED, BORED) | **5-8 GIFs** |
| Folder กลาง ๆ (SAD, ANGRY, HUNGRY, STARTLED, ADVENTURE) | **3-5 GIFs** |
| Folder เกิดน้อย (HOT, COLD, WORRIED, PANIC) | **2-3 GIFs** |

ไม่ต้องเต็ม pool — เริ่มจาก 2-3 ตัวต่อ folder ก่อน, ขยายภายหลังได้

---

## Easter egg GIFs

GIF ที่ไม่เข้าหมวด emotion ชัด — แนะนำใส่ใน folder ที่ใกล้สุดเป็น variety:

| GIF | แนะนำ folder |
|---|---|
| `den_pha`, `den_pha_ex` | ADVENTURE (night drive) หรือ WORRIED (เตือนเปิดไฟ) |
| `den_giao_thong` | STUCK |
| `police` | STARTLED |
| `music`, `neon` | ADVENTURE, EXCITED |
| `rain` | SAD, ADVENTURE |
| `sakura`, `yakura` | HAPPY, ADVENTURE |
| `keep_it_up` | READY, EXCITED |
| `cuoi_khinh_bi` (ยิ้มเย้ย) | BORED, STUCK |
| `noname` | HAPPY, BORED |

ใส่ซ้ำได้หลาย folder — แต่ละ folder reuse GIF ตัวเดียวกัน (just copy file)

---

## หลังจัดเสร็จ

1. บอก Claude ว่าจัดเสร็จแล้ว
2. Test pipeline: PC simulator → Android → ESP32 → robot เล่น GIF + WAV
3. Tune mapping ถ้าโทนไม่ match

## File format reminder

- GIF ต้อง **rotated CW + aspect 368:448 + black crush t=75** แล้ว (อยู่ใน `gif_crushed/` หมดแล้ว)
- ห้าม copy GIF จาก `gif/` (ต้นฉบับ landscape, ยังไม่ rotate) — เอาจาก `gif_crushed/` เท่านั้น
- ขนาดไฟล์เล็กยิ่งดี (bottleneck = LZW decode + WiFi transfer)
