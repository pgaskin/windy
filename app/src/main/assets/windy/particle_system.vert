// extracted from com.breel.wallpapers (11)

#version 310 es

precision lowp float;

uniform mat4 u_projTrans;

in vec2 a_texCoord0;
in vec3 a_position;

out vec2 v_uv;

void main() {
    v_uv = a_texCoord0;
    gl_Position = u_projTrans * vec4(a_position, 1.0);
}
