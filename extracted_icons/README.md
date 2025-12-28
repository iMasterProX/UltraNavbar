# Android 12 SystemUI Navigation Icons

Extracted from Android 12 SystemUI APK.

## Icons

| File | Description | Size |
|------|-------------|------|
| `ic_sysbar_back` | 뒤로가기 버튼 (3버튼 네비게이션) - 삼각형 | 28x28 |
| `ic_sysbar_home` | 홈 버튼 (3버튼 네비게이션) - 원형 | 28x28 |
| `ic_sysbar_recent` | 최근 앱 버튼 (3버튼 네비게이션) - 둥근 사각형 | 28x28 |
| `ic_sysbar_back_quick_step` | 뒤로가기 (제스처 네비게이션) - 화살표 | 28x28 |
| `ic_sysbar_home_quick_step` | 홈 핸들 (제스처 네비게이션) - 필 모양 | 28x28 |
| `ic_sysbar_docked` | 분할 화면/도킹 버튼 | 28x28 |
| `ic_sysbar_accessibility_button` | 접근성 버튼 - 사람 모양 | 21x21 (24 viewport) |

## File Formats

- `.xml` - Original Android VectorDrawable format
- `.svg` - Converted SVG format (white fill color)

## Usage Notes

- Original XML uses `?singleToneColor` for fill color (theme-based)
- SVG files use `#FFFFFF` (white) as default fill
- All icons use vector paths, so they scale without quality loss

## Path Data Reference

### Back Button (3-button)
```
M6.49,14.86c-0.66-0.39-0.66-1.34,0-1.73l6.02-3.53l5.89-3.46C19.11,5.73,20,6.26,20,7.1V14v6.9 c0,0.84-0.89,1.37-1.6,0.95l-5.89-3.46L6.49,14.86z
```

### Home Button (3-button)
```
M 14 7 C 17.8659932488 7 21 10.1340067512 21 14 C 21 17.8659932488 17.8659932488 21 14 21 C 10.1340067512 21 7 17.8659932488 7 14 C 7 10.1340067512 10.1340067512 7 14 7 Z
```

### Recent Button (3-button)
```
M19.9,21.5H8.1c-0.88,0-1.6-0.72-1.6-1.6V8.1c0-0.88,0.72-1.6,1.6-1.6h11.8c0.88,0,1.6,0.72,1.6,1.6v11.8 C21.5,20.78,20.78,21.5,19.9,21.5z
```
