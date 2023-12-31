#version 310 es

// extracted from com.breel.wallpapers (11)

precision highp float;

uniform mat4 u_projTrans;
uniform sampler2D u_texture;
uniform vec2 u_resolution;

in vec2 a_texCoord0;
in vec3 a_position;

out vec2 v_uv;

void main() {
    v_uv = a_texCoord0;
    gl_Position = u_projTrans * vec4(a_position, 1.0);
}
