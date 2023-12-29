#version 310 es

// extracted from com.breel.wallpapers (11)

precision lowp float;

uniform mat4 u_projTrans;
uniform vec2 u_resolution;
uniform sampler2D u_texture;
uniform float u_fadeDecay;

in vec3 a_position;
in vec2 a_texCoord0;

out vec2 v_uv;

void main() {
    gl_Position = u_projTrans * vec4(a_position, 1.0);
    v_uv = a_texCoord0;
}
