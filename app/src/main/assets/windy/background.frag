// extracted from com.breel.wallpapers (11)

#version 310 es

#define M_PI 3.1415926535897932384626433832795

precision lowp float;

uniform mat4 u_projTrans;
uniform sampler2D u_texture;
uniform sampler2D u_map;
uniform sampler2D u_vectorField;
uniform float u_mapAlpha;
uniform vec4 u_mapOutlineColor;
uniform float u_mapOutlineThickness;
uniform vec4 u_vectorFieldBounds;
uniform vec4 u_backgroundColor1;
uniform vec4 u_backgroundColor2;
uniform vec4 u_colorSlow;
uniform vec4 u_colorFast;
uniform vec2 u_size;
uniform float u_fboScale;

in highp vec2 v_uv;

out vec4 fragColor;

vec2 equirectangularToMercator(vec2 uv) {
    float latitude = (uv.y * 2. - 1.) * M_PI * .5;
    uv.y = atan(sinh(latitude));
    uv.y /= M_PI * 0.5;
    uv.y = uv.y * .5 + .5;
    return uv;
}

void main()  {
    vec2 uv = v_uv;

    highp vec2 backgroundUv = u_vectorFieldBounds.xy + v_uv * u_vectorFieldBounds.zw * u_size;
    backgroundUv = equirectangularToMercator(backgroundUv);

    float speed = texture(u_vectorField, backgroundUv).b;
    vec4 color = mix(u_backgroundColor1, u_backgroundColor2, speed);

    vec4 particles = texture(u_texture, uv);
    vec4 particlesColor = mix(u_colorSlow, u_colorFast, particles.r);
    fragColor = mix(color, particlesColor, particlesColor.a * particles.g * u_fboScale);
}
