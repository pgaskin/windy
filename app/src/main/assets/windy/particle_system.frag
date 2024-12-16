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

// texture is xyzw/rgba = x,y,life,unused

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
    // return vec2(sin(u_timeAcc), cos(u_timeAcc)) * .5; // debug: simple time-based movement
    // ^ huh... the circles drawn by that are jagged on the pixel but not the moto
    return vec2(sin(position.x), cos(u_timeAcc)) * .5; // debug: waves
    // ^ huh... the circles drawn by that are jagged on the pixel but not the moto
    highp vec2 uv = u_vectorFieldBounds.xy + position * u_vectorFieldBounds.zw * u_size;
    uv = equirectangularToMercator(uv);
    highp vec4 wind = texture(u_vectorField, uv);
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
    float isDead = 1.0 - step(0.0, life); // isDead = (life < 0.0) ? 1.0 ? 0.0

    // New particle position when dead
    vec2 vSeed = vec2(u_timeAcc);
    float r1 = rand(v_uv + vSeed);
    float r2 = rand(v_uv + vSeed * 2.0);
    vec2 randomPosition = vec2(r1, r2) * 1.2 - .1;

    // Resetting particle when dead
    newPosition = mix(newPosition, randomPosition, isDead); // newPosition = isDead ? randomPosition : newPosition
    life = mix(life, .2 + rand(v_uv.x * v_uv.y + vSeed) * .8, isDead); // life = isDead ? rand(.2 to .8) : life

    fragColor = vec4(newPosition, life, 1.0); // fragColor = { .x = newPosition.x, .y = newPosition.y, .z = life, .w = 1.0 }
}

// note: the bug on non-adreno gpus is almost definitely in here somewhere... or possibly in how we handle textures
// particle/trail works fine (I tested it individually)
// background, vector field, and coordinate transformation works fine (I compared screenshots of it in various forms -- original, speed only, background only, particles/trails only)
// something in here is causing jagged movement on the pixel
// todo: try doing something simple based on time in moveParticle to see if I can make them move consistently between devices
