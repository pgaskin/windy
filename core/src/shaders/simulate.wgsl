// Particle advection (replaces the GPGPU fragment-shader ping-pong).

@group(0) @binding(0) var<uniform> g: Globals;
@group(0) @binding(1) var<storage, read_write> particles: array<Particle>;
@group(0) @binding(2) var wind_tex: texture_2d<f32>;
@group(0) @binding(3) var wind_samp: sampler;

@compute @workgroup_size(64)
fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
    let i = gid.x;
    if (i >= g.particle_count) {
        return;
    }
    var p = particles[i];

    // particle motion (i.e., advection)
    let wind = wind_at(p.pos);
    let velocity = wind * g.wind_speed * g.time_delta * 0.2; // real time so this is independent of fps
    var new_pos = p.pos + velocity;

    var life = p.age - (g.time_delta / g.particle_life) * 0.3;

    // respawn dead particle if needed
    if (life <= 0.0) {
        let seed = p.uv + vec2<f32>(g.time_acc);
        let r1 = rand(seed);
        let r2 = rand(seed * 2.0);
        new_pos = vec2<f32>(r1, r2) * 1.2 - 0.1;
        life = 0.2 + rand(vec2<f32>(p.uv.x * p.uv.y + g.time_acc)) * 0.8;
        p.prev = new_pos; // don't draw a line from the old position
    } else {
        p.prev = p.pos;
    }

    // update particle
    p.pos = new_pos;
    p.age = life;
    particles[i] = p;
}

fn wind_at(pos: vec2<f32>) -> vec2<f32> {
    var uv = g.vector_field_bounds.xy + pos * g.vector_field_bounds.zw * g.size;
    uv = equirect_to_mercator(uv);
    let wind = textureSampleLevel(wind_tex, wind_samp, uv, 0.0);
    return (wind.xy - 0.5) * vec2<f32>(1.0, -1.0) * g.resolution;
}
