// extracted from com.breel.wallpapers (11)

#version 310 es

precision lowp float;

uniform mat4 u_projTrans;

in vec3 a_position;
in vec2 a_texCoord0;

out vec2 v_uv;

void main() {
    gl_Position = u_projTrans * vec4(a_position, 1.0);
    v_uv = a_texCoord0;
}
