// extracted from com.breel.wallpapers (11)

#version 310 es

precision lowp float;

uniform sampler2D u_texture;
uniform float u_fadeDecay;

in vec2 v_uv;

out vec4 fragColor;

void main() {
    fragColor = texture(u_texture, v_uv);
    fragColor.g *= u_fadeDecay;
}
