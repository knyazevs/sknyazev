<script lang="ts">
  import { onMount } from 'svelte';

  type OrbState = 'idle' | 'listening' | 'thinking' | 'speaking' | 'error';
  let { state = 'idle' }: { state: OrbState } = $props();

  let _s: OrbState = 'idle';
  $effect(() => { _s = state; });

  let canvas: HTMLCanvasElement;
  let animFrame: number;

  // State → shader parameters
  const PARAMS: Record<OrbState, {
    speed: number; brightness: number;
    tint: [number,number,number]; tintStr: number;
    pulse: number; size: number;
  }> = {
    idle:      { speed: 0.32, brightness: 1.00, tint: [1.00, 0.72, 0.38], tintStr: 0.15, pulse: 0.0, size: 1.00 },
    listening: { speed: 0.85, brightness: 1.30, tint: [0.45, 1.00, 0.55], tintStr: 0.55, pulse: 1.0, size: 1.08 },
    thinking:  { speed: 1.90, brightness: 1.15, tint: [1.00, 0.62, 0.22], tintStr: 0.35, pulse: 0.4, size: 1.04 },
    speaking:  { speed: 0.60, brightness: 1.55, tint: [1.00, 0.85, 0.45], tintStr: 0.38, pulse: 0.9, size: 1.12 },
    error:     { speed: 0.22, brightness: 0.82, tint: [1.00, 0.18, 0.08], tintStr: 0.72, pulse: 0.0, size: 0.90 },
  };

  // ── Vertex shader ──────────────────────────────────────────────────────────
  const VS = `#version 300 es
    in vec2 a_pos;
    void main() { gl_Position = vec4(a_pos, 0.0, 1.0); }
  `;

  // ── Fragment shader: warm domain-warped fluid ──────────────────────────────
  const FS = `#version 300 es
    precision highp float;

    uniform float iTime;
    uniform vec2  iResolution;
    uniform float u_speed;
    uniform float u_brightness;
    uniform vec3  u_tint;
    uniform float u_tint_str;
    uniform float u_pulse;
    uniform float u_size;
    uniform vec3  u_bg;

    out vec4 fragColor;

    // ── Gradient noise ────────────────────────────────────────────────────────
    vec2 hash2(vec2 p) {
      p = vec2(dot(p, vec2(127.1, 311.7)), dot(p, vec2(269.5, 183.3)));
      return -1.0 + 2.0 * fract(sin(p) * 43758.5453);
    }

    float gnoise(vec2 p) {
      vec2 i = floor(p);
      vec2 f = fract(p);
      vec2 u = f * f * (3.0 - 2.0 * f);
      return mix(
        mix(dot(hash2(i            ), f            ),
            dot(hash2(i + vec2(1,0)), f - vec2(1,0)), u.x),
        mix(dot(hash2(i + vec2(0,1)), f - vec2(0,1)),
            dot(hash2(i + vec2(1,1)), f - vec2(1,1)), u.x), u.y);
    }

    // Rotation matrix for FBM octave twist
    const mat2 ROT = mat2(0.80, 0.60, -0.60, 0.80);

    float fbm(vec2 p) {
      float f = 0.0;
      f += 0.5000 * gnoise(p); p = ROT * p * 2.02;
      f += 0.2500 * gnoise(p); p = ROT * p * 2.03;
      f += 0.1250 * gnoise(p); p = ROT * p * 2.01;
      f += 0.0625 * gnoise(p);
      return f / 0.9375;
    }

    void main() {
      vec2 uv = gl_FragCoord.xy / iResolution - 0.5;
      uv.y *= iResolution.y / iResolution.x;

      float rad = length(uv);

      // ── Orb shape (Gaussian falloff + pulse) ────────────────────────────────
      float pulse   = 1.0 + 0.07 * u_pulse * (0.5 + 0.5 * sin(iTime * u_speed * 2.2));
      float k       = 88.0 / (u_size * u_size * pulse * pulse);
      float orb     = exp(-rad * rad * k);
      float orbHard = smoothstep(0.0, 0.012, orb);  // crisp interior mask

      // Early exit far from any visible region
      if (orb < 0.003 && rad > u_size * 0.9) {
        fragColor = vec4(u_bg, 1.0);
        return;
      }

      // ── Domain-warped fluid ─────────────────────────────────────────────────
      float t = iTime * u_speed * 0.22;
      vec2  p = uv * 3.6;

      // First warp layer
      vec2 q = vec2(fbm(p + t * 0.70),
                    fbm(p + vec2(5.2, 1.3) + t * 0.55));

      // Second warp layer (deeper)
      vec2 r = vec2(fbm(p + 3.5*q + vec2(1.7, 9.2) + t * 0.42),
                    fbm(p + 3.5*q + vec2(8.3, 2.8) + t * 0.35));

      float f = fbm(p + 3.0*r + t * 0.18);

      // ── Slow veil: low-freq luminance drift (parallax without geometry) ────
      // Reused below for uneven "breathing" brightness
      float slow = fbm(uv * 1.2 + vec2(-t * 0.22, t * 0.14));
      f += slow * 0.18;

      // ── Warm amber-chocolate palette ────────────────────────────────────────
      // Remap f (≈ [-0.5, 0.5]) to a nice contrast curve
      float c = clamp(f * 0.85 + 0.55, 0.0, 1.0);
      c = c * c * (3.0 - 2.0 * c);  // extra smoothstep contrast

      vec3 col0 = vec3(0.04, 0.015, 0.003);  // charred brown (shadow)
      vec3 col1 = vec3(0.48, 0.16, 0.025);   // dark amber
      vec3 col2 = vec3(0.82, 0.44, 0.08);    // warm amber
      vec3 col3 = vec3(1.00, 0.78, 0.30);    // bright gold highlight
      vec3 col4 = vec3(1.00, 0.95, 0.78);    // incandescent core (near-white peach)

      vec3 col  = mix(col0, col1, smoothstep(0.00, 0.35, c));
      col       = mix(col,  col2, smoothstep(0.28, 0.65, c));
      col       = mix(col,  col3, smoothstep(0.58, 1.00, c));

      // Bright veins where warp distortion is strongest
      float vein = smoothstep(0.55, 1.0, length(q) * 0.85);
      col += col3 * vein * 0.35;

      // ── Incandescent core ────────────────────────────────────────────────
      // Tighter Gaussian; cools hue toward white-peach and boosts brightness
      // only where fluid is already bright → depth via temperature contrast
      float core    = exp(-rad * rad * k * 5.5);
      float coreAmt = core * smoothstep(0.35, 1.0, c);
      col  = mix(col, col4, coreAmt * 0.65);
      col += col4 * coreAmt * 0.35;

      // Subtle dark rim inside the orb edge for depth
      float rim = smoothstep(0.0, 0.3, 1.0 - orb * 3.0);
      col = mix(col, col * 0.35, rim * 0.5);

      // State tint
      col = mix(col, col * u_tint, u_tint_str * 0.42);

      // Uneven "breathing" — spatial brightness modulation from slow fbm
      // Range ≈ [0.90, 1.10]: orb tleet неравномерно, без ритмичного пульса
      col *= 1.0 + 0.22 * slow;

      col *= u_brightness;

      // Reinhard + mild gamma
      col  = col / (col + vec3(0.72));
      col  = pow(clamp(col, 0.0, 1.0), vec3(0.90));

      // ── Atmospheric glow around the orb ─────────────────────────────────────
      float glowFalloff = exp(-rad * rad * 16.0 / (u_size * u_size));
      vec3  glowCol     = vec3(0.52, 0.18, 0.03) * u_brightness * 0.55;
      // Tint the glow too
      glowCol = mix(glowCol, glowCol * u_tint, u_tint_str * 0.3);

      // ── Atmospheric background warmth ────────────────────────────────────────
      // Subtle warm vignette centered on screen — replaces CSS body::before
      float vignette = exp(-rad * rad * 1.4);
      vec3 bgWarm = u_bg + vec3(0.018, 0.008, 0.001) * vignette;

      // ── Composite ────────────────────────────────────────────────────────────
      vec3 result = bgWarm;
      result      = mix(result, col, orb);
      result     += glowCol * glowFalloff * (1.0 - orb * 0.65) * 0.85;

      fragColor = vec4(clamp(result, 0.0, 1.0), 1.0);
    }
  `;

  onMount(() => {
    const gl = canvas.getContext('webgl2');
    if (!gl) { console.warn('WebGL2 not supported'); return; }

    function compile(type: number, src: string) {
      const s = gl!.createShader(type)!;
      gl!.shaderSource(s, src);
      gl!.compileShader(s);
      if (!gl!.getShaderParameter(s, gl!.COMPILE_STATUS))
        console.error('Shader error:', gl!.getShaderInfoLog(s));
      return s;
    }

    const prog = gl.createProgram()!;
    gl.attachShader(prog, compile(gl.VERTEX_SHADER, VS));
    gl.attachShader(prog, compile(gl.FRAGMENT_SHADER, FS));
    gl.linkProgram(prog);
    if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) {
      console.error('Program link error:', gl.getProgramInfoLog(prog));
      return;
    }
    gl.useProgram(prog);

    const buf = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, buf);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1,-1, 1,-1, -1,1, 1,1]), gl.STATIC_DRAW);
    const posLoc = gl.getAttribLocation(prog, 'a_pos');
    gl.enableVertexAttribArray(posLoc);
    gl.vertexAttribPointer(posLoc, 2, gl.FLOAT, false, 0, 0);

    const U = {
      res:        gl.getUniformLocation(prog, 'iResolution'),
      time:       gl.getUniformLocation(prog, 'iTime'),
      speed:      gl.getUniformLocation(prog, 'u_speed'),
      brightness: gl.getUniformLocation(prog, 'u_brightness'),
      tint:       gl.getUniformLocation(prog, 'u_tint'),
      tintStr:    gl.getUniformLocation(prog, 'u_tint_str'),
      pulse:      gl.getUniformLocation(prog, 'u_pulse'),
      size:       gl.getUniformLocation(prog, 'u_size'),
      bg:         gl.getUniformLocation(prog, 'u_bg'),
    };

    // Match warm bg colors from global.css
    const getBg = () =>
      document.documentElement.getAttribute('data-theme') === 'light'
        ? [0.984, 0.961, 0.910] as number[]   // #FBF5E8
        : [0.047, 0.035, 0.020] as number[];   // #0C0905

    let bgColor = getBg();
    const themeObserver = new MutationObserver(() => { bgColor = getBg(); });
    themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });

    let cur = { ...PARAMS.idle, tint: [...PARAMS.idle.tint] as number[] };
    const lerp = (a: number, b: number, t: number) => a + (b - a) * t;
    const start = performance.now();

    function frame() {
      animFrame = requestAnimationFrame(frame);

      const tgt = PARAMS[_s];
      const k   = 0.022;
      cur.speed      = lerp(cur.speed,      tgt.speed,      k);
      cur.brightness = lerp(cur.brightness, tgt.brightness, k);
      cur.tintStr    = lerp(cur.tintStr,    tgt.tintStr,    k);
      cur.pulse      = lerp(cur.pulse,      tgt.pulse,      k);
      cur.size       = lerp(cur.size,       tgt.size,       k);
      for (let i = 0; i < 3; i++) cur.tint[i] = lerp(cur.tint[i], tgt.tint[i], k);

      const dpr = devicePixelRatio;
      const w   = Math.round(canvas.clientWidth  * dpr);
      const h   = Math.round(canvas.clientHeight * dpr);
      if (canvas.width !== w || canvas.height !== h) {
        canvas.width = w; canvas.height = h;
        gl!.viewport(0, 0, w, h);
      }

      const t = (performance.now() - start) / 1000;
      gl!.uniform2f(U.res, canvas.width, canvas.height);
      gl!.uniform1f(U.time,       t);
      gl!.uniform1f(U.speed,      cur.speed);
      gl!.uniform1f(U.brightness, cur.brightness);
      gl!.uniform3fv(U.tint,      cur.tint);
      gl!.uniform1f(U.tintStr,    cur.tintStr);
      gl!.uniform1f(U.pulse,      cur.pulse);
      gl!.uniform1f(U.size,       cur.size);
      gl!.uniform3fv(U.bg,        bgColor);
      gl!.drawArrays(gl!.TRIANGLE_STRIP, 0, 4);
    }

    frame();

    return () => {
      cancelAnimationFrame(animFrame);
      themeObserver.disconnect();
    };
  });
</script>

<canvas bind:this={canvas}></canvas>

<style>
  canvas {
    width: 100%;
    height: 100%;
    display: block;
  }
</style>
