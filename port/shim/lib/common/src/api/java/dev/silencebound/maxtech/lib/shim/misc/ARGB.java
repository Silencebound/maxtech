package dev.silencebound.maxtech.lib.shim.misc;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * 26.1's {@code net.minecraft.util.ARGB} for 1.21.1 (where the helpers live in {@code FastColor.ARGB32}, with other
 * names and fewer of them). Same names, arguments and results as 26.1; colours are 0xAARRGGBB.
 */
public final class ARGB {
    private static final short[] SRGB_TO_LINEAR = new short[256];
    private static final byte[] LINEAR_TO_SRGB = new byte[1024];

    static {
        for (int i = 0; i < SRGB_TO_LINEAR.length; i++) {
            SRGB_TO_LINEAR[i] = (short) Math.round(srgbToLinear(i / 255.0F) * 1023.0F);
        }
        for (int i = 0; i < LINEAR_TO_SRGB.length; i++) {
            LINEAR_TO_SRGB[i] = (byte) Math.round(linearToSrgb(i / 1023.0F) * 255.0F);
        }
    }

    private ARGB() {
    }

    private static float srgbToLinear(float x) {
        return x >= 0.04045F ? (float) Math.pow((x + 0.055) / 1.055, 2.4) : x / 12.92F;
    }

    private static float linearToSrgb(float x) {
        return x >= 0.0031308F ? (float) (1.055 * Math.pow(x, 1.0 / 2.4) - 0.055) : 12.92F * x;
    }

    public static float srgbToLinearChannel(int srgb) {
        return SRGB_TO_LINEAR[srgb] / 1023.0F;
    }

    public static int linearToSrgbChannel(float linear) {
        return LINEAR_TO_SRGB[Mth.floor(linear * 1023.0F)] & 0xFF;
    }

    public static int meanLinear(int srgb1, int srgb2, int srgb3, int srgb4) {
        return color((alpha(srgb1) + alpha(srgb2) + alpha(srgb3) + alpha(srgb4)) / 4,
                linearMean(red(srgb1), red(srgb2), red(srgb3), red(srgb4)),
                linearMean(green(srgb1), green(srgb2), green(srgb3), green(srgb4)),
                linearMean(blue(srgb1), blue(srgb2), blue(srgb3), blue(srgb4)));
    }

    private static int linearMean(int c1, int c2, int c3, int c4) {
        int linear = (SRGB_TO_LINEAR[c1] + SRGB_TO_LINEAR[c2] + SRGB_TO_LINEAR[c3] + SRGB_TO_LINEAR[c4]) / 4;
        return LINEAR_TO_SRGB[linear] & 0xFF;
    }

    public static int alpha(int color) {
        return color >>> 24;
    }

    public static int red(int color) {
        return color >> 16 & 0xFF;
    }

    public static int green(int color) {
        return color >> 8 & 0xFF;
    }

    public static int blue(int color) {
        return color & 0xFF;
    }

    public static int color(int alpha, int red, int green, int blue) {
        return (alpha & 0xFF) << 24 | (red & 0xFF) << 16 | (green & 0xFF) << 8 | blue & 0xFF;
    }

    public static int color(int red, int green, int blue) {
        return color(255, red, green, blue);
    }

    public static int color(Vec3 vec) {
        return color(as8BitChannel((float) vec.x()), as8BitChannel((float) vec.y()), as8BitChannel((float) vec.z()));
    }

    public static int multiply(int lhs, int rhs) {
        if (lhs == -1) {
            return rhs;
        }
        if (rhs == -1) {
            return lhs;
        }
        return color(alpha(lhs) * alpha(rhs) / 255, red(lhs) * red(rhs) / 255, green(lhs) * green(rhs) / 255,
                blue(lhs) * blue(rhs) / 255);
    }

    public static int addRgb(int lhs, int rhs) {
        return color(alpha(lhs), Math.min(red(lhs) + red(rhs), 255), Math.min(green(lhs) + green(rhs), 255),
                Math.min(blue(lhs) + blue(rhs), 255));
    }

    public static int subtractRgb(int lhs, int rhs) {
        return color(alpha(lhs), Math.max(red(lhs) - red(rhs), 0), Math.max(green(lhs) - green(rhs), 0),
                Math.max(blue(lhs) - blue(rhs), 0));
    }

    public static int multiplyAlpha(int color, float alphaMultiplier) {
        if (color == 0 || alphaMultiplier <= 0.0F) {
            return 0;
        }
        return alphaMultiplier >= 1.0F ? color : color(alphaFloat(color) * alphaMultiplier, color);
    }

    public static int scaleRGB(int color, float scale) {
        return scaleRGB(color, scale, scale, scale);
    }

    public static int scaleRGB(int color, float scaleR, float scaleG, float scaleB) {
        return color(alpha(color), Math.clamp((long) (int) (red(color) * scaleR), 0, 255),
                Math.clamp((long) (int) (green(color) * scaleG), 0, 255),
                Math.clamp((long) (int) (blue(color) * scaleB), 0, 255));
    }

    public static int scaleRGB(int color, int scale) {
        return color(alpha(color), Math.clamp((long) red(color) * scale / 255L, 0, 255),
                Math.clamp((long) green(color) * scale / 255L, 0, 255),
                Math.clamp((long) blue(color) * scale / 255L, 0, 255));
    }

    public static int greyscale(int color) {
        int grey = (int) (red(color) * 0.3F + green(color) * 0.59F + blue(color) * 0.11F);
        return color(alpha(color), grey, grey, grey);
    }

    public static int alphaBlend(int destination, int source) {
        int destinationAlpha = alpha(destination);
        int sourceAlpha = alpha(source);
        if (sourceAlpha == 255) {
            return source;
        }
        if (sourceAlpha == 0) {
            return destination;
        }
        int alpha = sourceAlpha + destinationAlpha * (255 - sourceAlpha) / 255;
        return color(alpha, blend(alpha, sourceAlpha, red(destination), red(source)),
                blend(alpha, sourceAlpha, green(destination), green(source)),
                blend(alpha, sourceAlpha, blue(destination), blue(source)));
    }

    private static int blend(int resultAlpha, int sourceAlpha, int destination, int source) {
        return (source * sourceAlpha + destination * (resultAlpha - sourceAlpha)) / resultAlpha;
    }

    public static int srgbLerp(float delta, int p0, int p1) {
        return color(Mth.lerpInt(delta, alpha(p0), alpha(p1)), Mth.lerpInt(delta, red(p0), red(p1)),
                Mth.lerpInt(delta, green(p0), green(p1)), Mth.lerpInt(delta, blue(p0), blue(p1)));
    }

    public static int linearLerp(float delta, int p0, int p1) {
        return color(Mth.lerpInt(delta, alpha(p0), alpha(p1)),
                LINEAR_TO_SRGB[Mth.lerpInt(delta, SRGB_TO_LINEAR[red(p0)], SRGB_TO_LINEAR[red(p1)])] & 0xFF,
                LINEAR_TO_SRGB[Mth.lerpInt(delta, SRGB_TO_LINEAR[green(p0)], SRGB_TO_LINEAR[green(p1)])] & 0xFF,
                LINEAR_TO_SRGB[Mth.lerpInt(delta, SRGB_TO_LINEAR[blue(p0)], SRGB_TO_LINEAR[blue(p1)])] & 0xFF);
    }

    public static int opaque(int color) {
        return color | 0xFF000000;
    }

    public static int transparent(int color) {
        return color & 0xFFFFFF;
    }

    public static int color(int alpha, int rgb) {
        return alpha << 24 | rgb & 0xFFFFFF;
    }

    public static int color(float alpha, int rgb) {
        return as8BitChannel(alpha) << 24 | rgb & 0xFFFFFF;
    }

    public static int white(float alpha) {
        return as8BitChannel(alpha) << 24 | 0xFFFFFF;
    }

    public static int white(int alpha) {
        return alpha << 24 | 0xFFFFFF;
    }

    public static int black(float alpha) {
        return as8BitChannel(alpha) << 24;
    }

    public static int black(int alpha) {
        return alpha << 24;
    }

    public static int gray(float brightness) {
        int channel = as8BitChannel(brightness);
        return color(channel, channel, channel);
    }

    public static int colorFromFloat(float alpha, float red, float green, float blue) {
        return color(as8BitChannel(alpha), as8BitChannel(red), as8BitChannel(green), as8BitChannel(blue));
    }

    public static Vector3f vector3fFromRGB24(int color) {
        return new Vector3f(redFloat(color), greenFloat(color), blueFloat(color));
    }

    public static Vector4f vector4fFromARGB32(int color) {
        return new Vector4f(redFloat(color), greenFloat(color), blueFloat(color), alphaFloat(color));
    }

    public static int average(int lhs, int rhs) {
        return color((alpha(lhs) + alpha(rhs)) / 2, (red(lhs) + red(rhs)) / 2, (green(lhs) + green(rhs)) / 2,
                (blue(lhs) + blue(rhs)) / 2);
    }

    public static int as8BitChannel(float value) {
        return Mth.floor(value * 255.0F);
    }

    public static float alphaFloat(int color) {
        return alpha(color) / 255.0F;
    }

    public static float redFloat(int color) {
        return red(color) / 255.0F;
    }

    public static float greenFloat(int color) {
        return green(color) / 255.0F;
    }

    public static float blueFloat(int color) {
        return blue(color) / 255.0F;
    }

    /** Swaps red and blue (ARGB to ABGR); the swap is its own inverse. */
    public static int toABGR(int color) {
        return color & 0xFF00FF00 | (color & 0xFF0000) >> 16 | (color & 0xFF) << 16;
    }

    public static int fromABGR(int color) {
        return toABGR(color);
    }

    /** Sets the HSB brightness of {@code color}, keeping hue, saturation and alpha. */
    public static int setBrightness(int color, float brightness) {
        int red = red(color);
        int green = green(color);
        int blue = blue(color);
        int max = Math.max(Math.max(red, green), blue);
        int min = Math.min(Math.min(red, green), blue);
        float range = max - min;
        float saturation = max != 0 ? range / max : 0.0F;
        if (saturation == 0.0F) {
            int grey = Math.round(brightness * 255.0F);
            return color(alpha(color), grey, grey, grey);
        }
        float hue;
        float r = (max - red) / range;
        float g = (max - green) / range;
        float b = (max - blue) / range;
        if (red == max) {
            hue = b - g;
        } else if (green == max) {
            hue = 2.0F + r - b;
        } else {
            hue = 4.0F + g - r;
        }
        hue /= 6.0F;
        if (hue < 0.0F) {
            hue++;
        }
        float segment = (hue - (float) Math.floor(hue)) * 6.0F;
        float offset = segment - (float) Math.floor(segment);
        float p = brightness * (1.0F - saturation);
        float q = brightness * (1.0F - saturation * offset);
        float t = brightness * (1.0F - saturation * (1.0F - offset));
        float nr;
        float ng;
        float nb;
        switch ((int) segment) {
            case 0 -> {
                nr = brightness;
                ng = t;
                nb = p;
            }
            case 1 -> {
                nr = q;
                ng = brightness;
                nb = p;
            }
            case 2 -> {
                nr = p;
                ng = brightness;
                nb = t;
            }
            case 3 -> {
                nr = p;
                ng = q;
                nb = brightness;
            }
            case 4 -> {
                nr = t;
                ng = p;
                nb = brightness;
            }
            default -> {
                nr = brightness;
                ng = p;
                nb = q;
            }
        }
        return color(alpha(color), Math.round(nr * 255.0F), Math.round(ng * 255.0F), Math.round(nb * 255.0F));
    }
}
