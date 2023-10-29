// extracted from com.breel.wallpapers (11)

#version 310 es

precision lowp float;

uniform mat4 u_projTrans;
uniform sampler2D u_positionTex;

in vec3 a_position;

out vec4 v_color;

float rand(vec2 co) {
    float a = 12.9898;
    float b = 78.233;
    float c = 43758.5453;
    float dt= dot(co.xy ,vec2(a,b));
    float sn= mod(dt,3.14);
    return fract(sin(sn) * c);
}

void main() {
    vec4 lookup = texture(u_positionTex, a_position.xy);
    vec2 position = lookup.xy;

    v_color = vec4(1.);

    v_color.r = step(0.7, rand(a_position.xy));

    // Opacity based on life
    v_color.g *= smoothstep(0.0, 0.05, 1.0 - lookup.z);
    v_color.g *= smoothstep(0.0, .25, lookup.z);

    gl_Position = u_projTrans * vec4(position, 0.0, 1.0);

    gl_PointSize = 3.;
}
