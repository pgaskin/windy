#version 310 es

// extracted from com.breel.wallpapers (11)

precision lowp float;

uniform mat4 u_projTrans;
uniform mat4 u_transform;
uniform vec2 u_resolution;
uniform vec2 u_size;

in vec3 a_position;
in vec2 a_texCoord0;

out lowp vec2 v_uv;

void main() {
    vec3 position = a_position;
    u_resolution; // unused
    position.xy *= u_size;
    gl_Position = u_projTrans * u_transform * vec4(position, 1.0);
    v_uv = a_texCoord0;
}
