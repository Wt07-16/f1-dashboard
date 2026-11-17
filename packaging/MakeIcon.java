import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 生成 F1 Dashboard 的 .icns 图标集 —— 沿用菜单栏托盘图标的设计:
 * F1 红(#E10600)圆角方块 + 2×2 方格旗(左上白、右下白)。
 * 每个尺寸单独矢量重绘(而非缩放),小尺寸边缘更干净。
 *
 * 用法:java packaging/MakeIcon.java <输出目录.iconset>
 */
public class MakeIcon {

    private static final Color F1_RED = new Color(0xE10600);

    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]);
        dir.mkdirs();
        int[][] sizes = {{16, 1}, {16, 2}, {32, 1}, {32, 2}, {128, 1},
                         {128, 2}, {256, 1}, {256, 2}, {512, 1}, {512, 2}};
        for (int[] s : sizes) {
            int px = s[0] * s[1];
            String name = "icon_" + s[0] + "x" + s[0] + (s[1] == 2 ? "@2x" : "") + ".png";
            ImageIO.write(render(px), "png", new File(dir, name));
            System.out.println("  " + name + "  (" + px + "×" + px + ")");
        }
    }

    /** 按像素边长绘制一次;所有几何量按比例换算,任何尺寸都保持同一形态 */
    private static BufferedImage render(int px) {
        BufferedImage img = new BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        double pad = px * 0.095;      // 四周留白(macOS 图标惯例,避免在 Dock 里显得过大)
        double size = px - 2 * pad;
        double r = size * 0.225;      // 圆角比例贴近 macOS Big Sur 之后的风格
        RoundRectangle2D body = new RoundRectangle2D.Double(pad, pad, size, size, r, r);

        if (px >= 64) {               // 小尺寸上投影只会糊成一团,故略过
            g.setColor(new Color(0, 0, 0, 10));
            g.fill(new RoundRectangle2D.Double(pad, pad + px * 0.015,
                    size, size, r, r));
        }

        g.setColor(F1_RED);
        g.fill(body);

        // 2×2 方格旗:左上、右下为白
        Shape old = g.getClip();
        g.clip(body);
        g.setColor(Color.WHITE);
        double half = size / 2;
        g.fill(new Rectangle2D.Double(pad, pad, half, half));
        g.fill(new Rectangle2D.Double(pad + half, pad + half, half, half));
        g.setClip(old);

        g.dispose();
        return img;
    }
}
