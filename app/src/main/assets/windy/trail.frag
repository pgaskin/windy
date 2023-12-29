#version 310 es

// extracted from com.breel.wallpapers (11)

precision lowp float;

uniform mat4 u_projTrans;
uniform vec2 u_resolution;
uniform sampler2D u_texture;
uniform float u_fadeDecay;

in vec2 v_uv;

out vec4 fragColor;

void main() {
    fragColor = texture(u_texture, v_uv);
    fragColor.g *= u_fadeDecay;
}
