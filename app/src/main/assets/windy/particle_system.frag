#version 310 es

// extracted from com.breel.wallpapers (11)

#define M_PI 3.1415926535897932384626433832795

precision highp float;

uniform mat4 u_projTrans;
uniform sampler2D u_vectorField;
uniform sampler2D u_texture;
uniform vec4 u_vectorFieldBounds;
uniform float u_timeDelta;
uniform float u_timeAcc;
uniform float u_windSpeed;
uniform float u_particleLife;
uniform vec2 u_resolution;
uniform vec2 u_size;

in vec2 v_uv;

out vec4 fragColor;

vec2 equirectangularToMercator(vec2 uv) {
    float latitude = (uv.y * 2. - 1.) * M_PI * .5;
    uv.y = atan(sinh(latitude));
    uv.y /= M_PI * 0.5;
    uv.y = uv.y * .5 + .5;
    return uv;
}

float rand(vec2 co) {
    float a = 12.9898;
    float b = 78.233;
    float c = 43758.5453;
    float dt= dot(co.xy ,vec2(a,b));
    float sn= mod(dt,3.14);
    return fract(sin(sn) * c);
}

vec2 windSpeed(vec2 position) {
    vec2 uv = u_vectorFieldBounds.rg + position * u_vectorFieldBounds.ba * u_size;
    uv = equirectangularToMercator(uv);
    vec4 wind = texture(u_vectorField, uv);
    return (wind.xy - 0.5) * vec2(1.0, -1.0) * u_resolution;
}

vec2 moveParticle(vec2 position) {
    // Get wind data at specific position
    vec2 wind = windSpeed(position);

    // Calculate speed based on FPS and uniform var
    vec2 velocity = wind * u_windSpeed * u_timeDelta * .2;

    // Return new position
    return position + velocity;
}

void main() {
    vec4 particleData = texture(u_texture, v_uv);

    // Calculate 2 points ahead so we can pick a point on a spline
    // using only the next position will give us blocky shapes
    vec2 p0 = particleData.xy;

    // Get smooth point on the 3 vertices spline
    vec2 newPosition = moveParticle(p0);

    // Age particle
    float life = particleData.z - (u_timeDelta / u_particleLife) * .3;
    float isDead = 1.0 - step(0.0, life);

    // New particle position when dead
    vec2 vSeed = vec2(u_timeAcc);
    float r1 = rand(v_uv + vSeed);
    float r2 = rand(v_uv + vSeed * 2.0);
    vec2 randomPosition = vec2(r1, r2) * 1.2 - .1;

    // Resetting particle when dead
    newPosition = mix(newPosition, randomPosition, isDead);
    life = mix(life, .2 + rand(v_uv.x * v_uv.y + vSeed) * .8, isDead);

    fragColor = vec4(newPosition, life, 1.0);
}
