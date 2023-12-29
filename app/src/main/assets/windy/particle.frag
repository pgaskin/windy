#version 310 es

// extracted from com.breel.wallpapers (11)

precision lowp float;

uniform mat4 u_projTrans;
uniform vec2 u_resolution;
uniform float u_particleOpacity;

in vec4 v_color;

out vec4 fragColor;

void main() {
    vec2 uv2 = (gl_PointCoord - 0.5) * 2.0;

    fragColor = v_color;

    fragColor.a = 1.-smoothstep(0.1, 1., uv2.x * uv2.x + uv2.y * uv2.y);
    fragColor.a *= u_particleOpacity;
}
