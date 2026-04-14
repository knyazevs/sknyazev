<script lang="ts">
  import { onMount } from 'svelte';

  type OrbState = 'idle' | 'listening' | 'thinking' | 'speaking' | 'error';
  let { state = 'idle' }: { state: OrbState } = $props();

  let _s: OrbState = 'idle';
  $effect(() => { _s = state; });

  let canvas: HTMLCanvasElement;
  let animFrame: number;

  const PARAMS: Record<OrbState, { speed: number; brightness: number; tint: [number,number,number]; tintStr: number; pulse: number; size: number }> = {
    idle:      { speed: 0.50, brightness: 1.10, tint: [0.75, 0.82, 1.00], tintStr: 0.45, pulse: 0.0, size: 1.00 },
    listening: { speed: 1.10, brightness: 1.40, tint: [0.30, 1.00, 0.55], tintStr: 0.50, pulse: 1.0, size: 1.10 },
    thinking:  { speed: 2.00, brightness: 1.25, tint: [0.40, 0.72, 1.00], tintStr: 0.45, pulse: 0.5, size: 1.05 },
    speaking:  { speed: 0.85, brightness: 1.70, tint: [0.80, 0.68, 1.00], tintStr: 0.45, pulse: 1.0, size: 1.15 },
    error:     { speed: 0.30, brightness: 0.90, tint: [1.00, 0.28, 0.28], tintStr: 0.60, pulse: 0.0, size: 0.90 },
  };

  const VS = `#version 300 es
    in vec2 a_pos;
    void main() { gl_Position = vec4(a_pos, 0.0, 1.0); }
  `;

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

    #define ITER       15
    #define FPARAM     0.53
    #define VSTEPS     20
    #define SSTEP      0.1
    #define ZOOM       0.800
    #define TILE       0.850
    #define BRIGHT     0.0015
    #define DARKMATTER 0.300
    #define DFADE      0.730
    #define SAT        0.850

    mat2 rot2(float a) { float s=sin(a),c=cos(a); return mat2(c,-s,s,c); }

    vec4 starfield(vec3 ro, vec3 rd) {
      float s = 0.1, fade = 1.0;
      vec3 v = vec3(0.0);
      for (int r = 0; r < VSTEPS; r++) {
        vec3 p = ro + s*rd*0.5;
        p = abs(vec3(TILE) - mod(p, vec3(TILE*2.0)));
        float pa = 0.0, a = 0.0;
        for (int i = 0; i < ITER; i++) {
          p   = abs(p)/dot(p,p) - FPARAM;
          p.xy *= rot2(iTime * u_speed * 0.0015);
          a  += abs(length(p) - pa);
          pa  = length(p);
        }
        float dm = max(0.0, DARKMATTER - a*a*0.001);
        a = a*a*a;
        if (r > 6) fade *= 1.3 - dm;
        v += fade;
        v += vec3(s, s*s, s*s*s*s) * a * BRIGHT * fade;
        fade *= DFADE;
        s   += SSTEP;
      }
      v = mix(vec3(length(v)), v, SAT);
      return vec4(v * 0.03, 1.0);
    }

    void main() {
      vec2 fc = gl_FragCoord.xy;
      vec2 uv = fc/iResolution - 0.5;
      uv.y  *= iResolution.y/iResolution.x;

      float r     = length(uv);
      float angle = atan(uv.y, uv.x);

      // Турбулентность масштабируется с расстоянием:
      // в центре = 0, на краях = максимум → ядро чистое, граница растворяется
      float turb = 0.0;
      turb += 0.028 * sin(angle * 2.0 + iTime * u_speed * 0.38 + 1.30);
      turb += 0.020 * sin(angle * 5.0 - iTime * u_speed * 0.61 + 2.70);
      turb += 0.014 * sin(angle * 9.0 + iTime * u_speed * 0.95 + 0.50);
      turb += 0.009 * sin(angle * 15.0 - iTime * u_speed * 0.44 + 4.10);
      turb += 0.006 * sin(angle * 24.0 + iTime * u_speed * 1.30 + 1.80);
      turb += 0.010 * sin(r * 10.0 - iTime * u_speed * 0.60 + 3.20);
      // Чем дальше от центра, тем сильнее рвёт контур
      float edgeScale = smoothstep(0.0, 0.28 * u_size, r);
      turb *= edgeScale * 1.8;

      float effectiveR = r + turb;

      // Гауссовый спад: центр = 1.0, края = почти 0, никакой чёткой границы
      // k контролирует ширину: больше k = компактнее, резче спад у краёв
      float baseK = 112.0;
      float k = baseK / (u_size * u_size);
      // При пульсе слегка расширяем
      float pulseExpand = 1.0 + 0.08 * u_pulse * (0.5 + 0.5 * sin(iTime * u_speed * 2.1));
      float falloff = exp(-effectiveR * effectiveR * k / (pulseExpand * pulseExpand));

      // Ранний выход там где точно ничего не будет видно
      if (falloff < 0.004) {
        fragColor = vec4(u_bg, 1.0);
        return;
      }

      // ── Космос ─────────────────────────────────────────────────────────────
      vec3 dir = vec3(uv * ZOOM, 1.0);

      float t3 = iTime*u_speed*0.1
               + (0.25 + 0.05*sin(iTime*0.1)) / (length(uv) + 0.07) * 1.2;
      mat2 ma = mat2(cos(t3), sin(t3), -sin(t3), cos(t3));

      vec2  u2 = abs(fc + fc - iResolution) / iResolution.y;
      vec2  v2 = iResolution;
      vec4  z  = vec4(1.0, 2.0, 3.0, 0.0);
      vec4  o  = z;
      float a4 = 0.5;
      float t4 = iTime * u_speed * 0.1;

      for (float i = 1.0; i < 13.0; i += 1.0) {
        t4 += 1.0;
        a4 += 0.03;
        vec4 cv = cos(i + 0.02*t4 - vec4(0.0, 11.0, 33.0, 0.0));
        u2 *= mat2(cv[0], cv[1], cv[2], cv[3]);
        v2   = cos(t4 - 7.0*u2*pow(a4, i)) - 0.1*u2;
        u2  += tanh(400.0 * dot(u2,u2) * cos(100.0*u2.yx + t4)) / 200.0
             + 0.2 * a4 * u2
             + cos(4.0/exp(dot(o,o)/100.0) + t4) / 300.0;
        u2.xy *= ma;
        float dv    = dot(v2, v2);
        float duu   = dot(u2, u2);
        vec2  arg   = 1.5*u2 / max(0.5 - duu, 1e-4) - 10.0*u2.yx + t4;
        float denom = length((1.0 + i*dv) * sin(arg));
        o += (1.0 + cos(z + t4)) / max(denom, 1e-5);
      }

      o = 25.6 / (min(o, 13.0) + 164.0/max(o, 1e-5)) - dot(u2,u2)/250.0;

      vec4 stars = starfield(vec3(1.0, 0.5, 0.5), dir);
      // o при малом весе добавляет цветовую вариацию без заметных лучей
      vec4 cosmos = stars * mix(vec4(1.0), clamp(o, 0.1, 2.0), 0.15);
      cosmos.rgb *= u_brightness;
      cosmos.rgb  = mix(cosmos.rgb, cosmos.rgb * u_tint, max(u_tint_str, 0.20));

      // Reinhard tone mapping — предотвращает клиппинг в белый при любой яркости
      cosmos.rgb /= (cosmos.rgb + vec3(1.0));

      // На тёмном фоне сущность светлее bg, на светлом — темнее (тёмная туманность)
      float bgLum    = dot(u_bg, vec3(0.299, 0.587, 0.114));
      float lumBoost = mix(2.8, 1.0, smoothstep(0.3, 0.7, bgLum));
      cosmos.rgb *= lumBoost;

      // mix() — плавный переход к bg без клиппинга
      fragColor.rgb = mix(u_bg, cosmos.rgb, falloff);
      fragColor.a   = 1.0;
    }
  `;

  onMount(() => {
    const gl = canvas.getContext('webgl2');
    if (!gl) { console.warn('WebGL2 not supported'); return; }

    function compile(type: number, src: string) {
      const s = gl!.createShader(type)!;
      gl!.shaderSource(s, src);
      gl!.compileShader(s);
      if (!gl!.getShaderParameter(s, gl!.COMPILE_STATUS)) {
        console.error('Shader error:', gl!.getShaderInfoLog(s));
      }
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

    const getBg = () =>
      document.documentElement.getAttribute('data-theme') === 'light'
        ? [0.957, 0.957, 0.980] as number[]
        : [0.039, 0.039, 0.059] as number[];

    let bgColor = getBg();

    const themeObserver = new MutationObserver(() => { bgColor = getBg(); });
    themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });

    let cur = {
      speed: 0.50, brightness: 2.20,
      tint: [0.75, 0.82, 1.0] as number[],
      tintStr: 0.0, pulse: 0.0, size: 1.0
    };
    const lerp = (a: number, b: number, t: number) => a + (b - a) * t;
    const start = performance.now();

    function frame() {
      animFrame = requestAnimationFrame(frame);

      const tgt = PARAMS[_s];
      const k = 0.02;
      cur.speed      = lerp(cur.speed,      tgt.speed,      k);
      cur.brightness = lerp(cur.brightness, tgt.brightness, k);
      cur.tintStr    = lerp(cur.tintStr,    tgt.tintStr,    k);
      cur.pulse      = lerp(cur.pulse,      tgt.pulse,      k);
      cur.size       = lerp(cur.size,       tgt.size,       k);
      for (let i = 0; i < 3; i++) cur.tint[i] = lerp(cur.tint[i], tgt.tint[i], k);

      const dpr = devicePixelRatio;
      const w = Math.round(canvas.clientWidth  * dpr);
      const h = Math.round(canvas.clientHeight * dpr);
      if (canvas.width !== w || canvas.height !== h) {
        canvas.width = w; canvas.height = h;
        gl!.viewport(0, 0, w, h);
      }

      const t = (performance.now() - start) / 1000;
      gl!.uniform2f(U.res, canvas.width, canvas.height);
      gl!.uniform1f(U.time, t);
      gl!.uniform1f(U.speed, cur.speed);
      gl!.uniform1f(U.brightness, cur.brightness);
      gl!.uniform3fv(U.tint, cur.tint);
      gl!.uniform1f(U.tintStr, cur.tintStr);
      gl!.uniform1f(U.pulse, cur.pulse);
      gl!.uniform1f(U.size, cur.size);
      gl!.uniform3fv(U.bg, bgColor);

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
