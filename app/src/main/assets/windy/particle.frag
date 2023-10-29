// extracted from com.breel.wallpapers (11)

#version 310 es

precision lowp float;

uniform float u_particleOpacity;
in vec4 v_color;

out vec4 fragColor;

void main() {
    vec2 uv2 = (gl_PointCoord - 0.5) * 2.0;

    fragColor = v_color;

    fragColor.a = 1.-smoothstep(0.1, 1., uv2.x * uv2.x + uv2.y * uv2.y);
    fragColor.a *= u_particleOpacity;
}