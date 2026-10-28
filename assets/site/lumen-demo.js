/* ==========================================================================
   Lumen Glass — live glass demo
   https://subhankarcodegeek.github.io/lumen-glass/

   A faithful WebGL port of the library's own algorithm:

     GlassMath.kt          -> sdRoundRect, squircle, lens displacement,
                              dispersionScales (per-channel radial scaling)
     LumenGlassShader.kt   -> the four rim profiles, dual-lobe lighting, bezel
     QualityTier.kt        -> HIGH / BALANCED feature gating

   This is the same maths, not a lookalike: the SDF, the boundary normal derived
   from forward finite differences, the rim curves, the dispersion scales and the
   hairline stroke all mirror the Kotlin. It is NOT the Android AGSL runtime — it
   is that algorithm transcribed to GLSL and run in a browser, and the page says
   so in as many words.

   Engineering decisions that matter:
     * The backdrop is ours (gradients + a wordmark on a Canvas2D texture), so
       sampling it is well defined. The usual blocker for glass on the web is
       getting a live DOM backdrop into a texture; we sidestep it legitimately.
     * Rendering is on demand: pointer move, control change, resize. Idle costs
       nothing, so leaving the page open does not drain a battery.
     * The backing store is capped at DPR 2 (1.75 on coarse pointers). The
       material is blurry by design, so this is invisible and much cheaper.
     * Adaptive render scale steps down when frames are expensive, and reports
       what it dropped — mirroring the library's own detectability contract.

   Fallback ladder: WebGL -> CSS panel -> static poster. The note text is rewritten
   to match whichever tier is live, so the page never claims more than it does.
   ========================================================================== */

(function () {
  'use strict';

  var reduceMotion = window.matchMedia
    ? window.matchMedia('(prefers-reduced-motion: reduce)')
    : { matches: false };

  var coarsePointer = window.matchMedia
    ? window.matchMedia('(hover: none)').matches
    : false;

  /* ---------------------------------------------------------------------- */
  /* Shader                                                                  */
  /* ---------------------------------------------------------------------- */

  var VERTEX_SRC = [
    'attribute vec2 aPos;',
    'void main() { gl_Position = vec4(aPos, 0.0, 1.0); }'
  ].join('\n');

  var FRAGMENT_SRC = [
    '#ifdef GL_FRAGMENT_PRECISION_HIGH',
    'precision highp float;',
    '#else',
    'precision mediump float;',
    '#endif',
    '',
    'uniform vec2      uResolution;   // drawing buffer, px',
    'uniform vec2      uCenter;       // glass centre in that space, px (y down)',
    'uniform vec2      uHalfSize;     // glass half extent, px',
    'uniform float     uCornerRadius; // px',
    'uniform float     uRimWidth;     // px',
    'uniform sampler2D uBackdrop;',
    '',
    'uniform float uRefraction;',
    'uniform float uDispersion;',
    'uniform float uExponent;   // squircle exponent for the field',
    'uniform float uRimProfile; // 0 toric, 1 smooth, 2 droplet, 3 legacy',
    'uniform float uEdgeLight;',
    'uniform float uTint;',
    'uniform float uSaturation;',
    'uniform float uContrast;',
    'uniform float uBezelAlpha;',
    'uniform float uBezelPx;',
    'uniform float uFrost;      // 0..1, drives the fixed-tap blur',
    'uniform float uCentreW;    // LensModel weights, already clamped',
    'uniform float uRimW;',
    'uniform vec2  uLight;      // to-light vector, normalised',
    '',
    '// Mirrors GlassMath.roundedRectSdf.',
    'float sdRoundRect(vec2 p, vec2 halfSize, float r) {',
    '  r = min(r, min(halfSize.x, halfSize.y));',
    '  vec2 q = abs(p) - (halfSize - vec2(r));',
    '  return length(max(q, vec2(0.0))) + min(max(q.x, q.y), 0.0) - r;',
    '}',
    '',
    'vec3 sampleAt(vec2 frag, vec2 offset) {',
    '  return texture2D(uBackdrop, (frag + offset) / uResolution).rgb;',
    '}',
    '',
    '// Per-channel radial scale about the glass centre, exactly as',
    '// GlassMath.dispersionScales: red scales by 1+d, green by 1, blue by 1-d.',
    '// Zero at the optical centre, growing toward the rim.',
    'vec3 sampleDispersed(vec2 frag, vec2 offset, float d) {',
    '  if (d <= 0.0005) return sampleAt(frag, offset);',
    '  vec2 rel = (frag + offset) - uCenter;',
    '  vec3 rgb;',
    '  rgb.r = texture2D(uBackdrop, (uCenter + rel * (1.0 + d)) / uResolution).r;',
    '  rgb.g = texture2D(uBackdrop, (uCenter + rel) / uResolution).g;',
    '  rgb.b = texture2D(uBackdrop, (uCenter + rel * (1.0 - d)) / uResolution).b;',
    '  return rgb;',
    '}',
    '',
    'void main() {',
    '  // Flip Y so fragment coordinates match canvas coordinates (y down).',
    '  vec2 frag = vec2(gl_FragCoord.x, uResolution.y - gl_FragCoord.y);',
    '  vec2 p = frag - uCenter;',
    '',
    '  float sdf = sdRoundRect(p, uHalfSize, uCornerRadius);',
    '  float aa = max(1.0, uCornerRadius * 0.02);',
    '  float coverage = 1.0 - smoothstep(-aa, aa, sdf);',
    '',
    '  vec3 outside = sampleAt(frag, vec2(0.0));',
    '',
    '  // Soft ambient shadow just outside the rim, so the panel reads as sitting',
    '  // above the stage rather than pasted onto it.',
    '  float shadow = smoothstep(0.0, uCornerRadius * 2.2, sdf);',
    '  outside *= 1.0 - (1.0 - shadow) * 0.30;',
    '',
    '  if (coverage <= 0.0001) {',
    '    gl_FragColor = vec4(outside, 1.0);',
    '    return;',
    '  }',
    '',
    '  // Outward boundary normal via forward finite differences, as in',
    '  // LumenGlassShader.kt.',
    '  vec2 normal = vec2(',
    '    sdRoundRect(p + vec2(1.0, 0.0), uHalfSize, uCornerRadius) - sdf,',
    '    sdRoundRect(p + vec2(0.0, 1.0), uHalfSize, uCornerRadius) - sdf',
    '  );',
    '  float nLen = length(normal);',
    '  normal = nLen > 0.0001 ? normal / nLen : vec2(0.0, -1.0);',
    '',
    '  // Aspect-agnostic superellipse field: 0 at the centre, ~1 at the rim.',
    '  vec2 nx = p / max(uHalfSize, vec2(1.0));',
    '  float sq = clamp(pow(abs(nx.x), uExponent) + pow(abs(nx.y), uExponent), 0.0, 1.0);',
    '  float scale = min(uHalfSize.x, uHalfSize.y);',
    '',
    '  float rimWidth = max(uRimWidth, 1.0);',
    '  float distFromEdge = max(0.0, -sdf);',
    '  float u = clamp(1.0 - distFromEdge / rimWidth, 0.0, 1.0);',
    '',
    '  // ---- Rim-weighted displacement: the four profiles, verbatim ----------',
    '  vec2 offset = vec2(0.0);',
    '  if (uRimProfile < 0.5) {',
    '    // Pattern A: toric meniscus, the canonical lens arc.',
    '    float toric = 1.0 - sqrt(max(0.0, 1.0 - u * u));',
    '    float maxDisp = rimWidth * min(0.48 * uRimW * uRefraction, 0.52);',
    '    offset = -normal * (toric * maxDisp);',
    '  } else if (uRimProfile < 1.5) {',
    '    // Pattern B: smooth Hermite fillet.',
    '    float smoothCurve = 0.30 * u + 0.70 * (u * u);',
    '    float maxDisp = rimWidth * min(0.40 * uRimW * uRefraction, 0.48);',
    '    offset = -normal * (smoothCurve * maxDisp);',
    '  } else if (uRimProfile < 2.5) {',
    '    // Pattern C: bipolar droplet meniscus — bulges out, then tucks under.',
    '    float bulge = sin(u * 3.14159265);',
    '    float inwardZoom = 1.0 - sqrt(max(0.0, 1.0 - u * u));',
    '    float maxDisp = rimWidth * min(0.45 * uRimW * uRefraction, 0.50);',
    '    offset = normal * (bulge * 0.35 * maxDisp) - normal * (inwardZoom * maxDisp);',
    '  } else {',
    '    // Legacy: normal-blended squircle field, no rim curvature of its own.',
    '    vec2 dispDir = mix(-nx, -normal, sq * sq);',
    '    offset = dispDir * (sq * uRimW * uRefraction) * scale * 0.5;',
    '  }',
    '',
    '  // Centre-weighted lens term (the LensModel.Blend centre component).',
    '  offset += -nx * ((1.0 - sq) * uCentreW * uRefraction) * scale * 0.05;',
    '',
    '  vec3 color = sampleDispersed(frag, offset, uDispersion);',
    '',
    '  // Frost: a small fixed-tap blur. Deliberately not a big kernel — Android',
    '  // uses the platform\u2019s separable Gaussian; here it only has to read as',
    '  // frosted so the contrast with the clear rim is legible. Weighted toward the',
    '  // interior so the rim keeps the crisper sample and the bending stays sharp.',
    '  if (uFrost > 0.01) {',
    '    float r = 1.5 + uFrost * 5.0;',
    '    vec3 blur = sampleAt(frag, offset + vec2(-r, -r))',
    '              + sampleAt(frag, offset + vec2( r, -r))',
    '              + sampleAt(frag, offset + vec2(-r,  r))',
    '              + sampleAt(frag, offset + vec2( r,  r));',
    '    blur *= 0.25;',
    '    color = mix(color, blur, uFrost * (1.0 - u) * 0.7);',
    '  }',
    '',
    '  // Grade: luma-preserving saturation, then contrast.',
    '  float luma = dot(color, vec3(0.2126, 0.7152, 0.0722));',
    '  color = mix(vec3(luma), color, uSaturation);',
    '  color = clamp((color - 0.5) * uContrast + 0.5, 0.0, 1.0);',
    '',
    '  // Material tint.',
    '  color = mix(color, vec3(1.0), uTint);',
    '',
    '  // ---- Dual-lobe lighting ---------------------------------------------',
    '  float directional = clamp(dot(normal, uLight), 0.0, 1.0);',
    '',
    '  // 360 degree hairline stroke with a 28% ambient floor, plus a directional',
    '  // specular sheen and a short Fresnel band inside the edge.',
    '  float strokeFactor = 0.28 + 0.72 * pow(directional, 1.2);',
    '  float strokeWidthPx = clamp(uCornerRadius * 0.055, 1.4, 2.4);',
    '  float stroke = smoothstep(-strokeWidthPx - 0.75, -strokeWidthPx + 0.1, sdf)',
    '               * (1.0 - smoothstep(0.0, 0.75, sdf));',
    '  float fresnel = (1.0 - smoothstep(-6.0, 0.0, sdf)) * 0.22 * pow(directional, 1.5);',
    '  color += vec3((stroke * strokeFactor * 2.6 + fresnel * 1.4) * uEdgeLight);',
    '',
    '  // Bezel: inner shadow on edges facing away from the light (HIGH tier only;',
    '  // uBezelAlpha arrives as 0 elsewhere).',
    '  if (uBezelAlpha > 0.0) {',
    '    float bezel = 1.0 - smoothstep(-max(uBezelPx, 0.0001), 0.0, sdf);',
    '    float shadowDir = 0.25 + 0.75 * clamp(dot(normal, -uLight), 0.0, 1.0);',
    '    color *= 1.0 - bezel * uBezelAlpha * 0.7 * shadowDir;',
    '  }',
    '',
    '  vec3 finalColor = mix(outside, color, coverage);',
    '  gl_FragColor = vec4(finalColor, 1.0);',
    '}'
  ].join('\n');

  /* ---------------------------------------------------------------------- */
  /* Backdrop — our own stage, so it is already a texture                    */
  /* ---------------------------------------------------------------------- */

  function drawBackdrop(canvas) {
    var ctx = canvas.getContext('2d');
    if (!ctx) return false;

    var w = canvas.width;
    var h = canvas.height;
    if (!w || !h) return false;

    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.clearRect(0, 0, w, h);

    ctx.fillStyle = '#0a0912';
    ctx.fillRect(0, 0, w, h);

    function blob(cx, cy, radius, inner, outer) {
      var g = ctx.createRadialGradient(
        cx * w, cy * h, 0,
        cx * w, cy * h,
        radius * Math.max(w, h)
      );
      g.addColorStop(0, inner);
      g.addColorStop(1, outer);
      ctx.fillStyle = g;
      ctx.fillRect(0, 0, w, h);
    }

    blob(0.16, 0.2, 0.52, 'rgba(255,138,61,0.95)', 'rgba(255,138,61,0)');
    blob(0.74, 0.26, 0.55, 'rgba(255,45,149,0.92)', 'rgba(255,45,149,0)');
    blob(0.3, 0.88, 0.58, 'rgba(42,107,255,0.92)', 'rgba(42,107,255,0)');
    blob(0.88, 0.82, 0.52, 'rgba(20,224,200,0.85)', 'rgba(20,224,200,0)');

    // The wordmark sits in the sampled layer on purpose: its letterforms cross
    // the rim, which is what makes the refraction legible.
    ctx.save();
    ctx.globalAlpha = 0.92;
    ctx.fillStyle = '#ffffff';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    var size = Math.min(w * 0.3, h * 0.62);
    ctx.font = '700 ' + size + 'px Inter, -apple-system, "Segoe UI", Roboto, sans-serif';
    var text = 'LUMEN';
    var tracking = size * 0.06;
    var widths = [];
    var total = 0;
    var i;
    for (i = 0; i < text.length; i++) {
      widths.push(ctx.measureText(text[i]).width);
      total += widths[i] + tracking;
    }
    total -= tracking;
    var x = w * 0.5 - total * 0.5;
    for (i = 0; i < text.length; i++) {
      ctx.fillText(text[i], x + widths[i] * 0.5, h * 0.5);
      x += widths[i] + tracking;
    }
    ctx.restore();

    // Concentric rings: curved arcs read as *bending* when the lens displaces
    // them, which a smooth gradient cannot show.
    ctx.save();
    ctx.strokeStyle = 'rgba(255,255,255,0.30)';
    ctx.lineWidth = Math.max(1.5, w / 700);
    var ringStep = Math.min(w, h) * 0.075;
    for (var radius = Math.min(w, h) * 0.12;
         radius < Math.max(w, h) * 0.75;
         radius += ringStep) {
      ctx.beginPath();
      ctx.arc(w * 0.5, h * 0.5, radius, 0, Math.PI * 2);
      ctx.stroke();
    }
    ctx.restore();

    // A dense, high-contrast grid. Straight lines are the clearest proof that
    // content is displaced rather than merely blurred: without high-frequency
    // detail a lens has almost nothing visible to bend.
    ctx.save();
    ctx.strokeStyle = 'rgba(255,255,255,0.32)';
    ctx.lineWidth = Math.max(2, w / 480);
    var step = Math.max(18, w / 40);
    var gx;
    var gy;
    for (gx = (w % step) / 2; gx < w; gx += step) {
      ctx.beginPath();
      ctx.moveTo(gx, 0);
      ctx.lineTo(gx, h);
      ctx.stroke();
    }
    for (gy = (h % step) / 2; gy < h; gy += step) {
      ctx.beginPath();
      ctx.moveTo(0, gy);
      ctx.lineTo(w, gy);
      ctx.stroke();
    }
    ctx.restore();

    // Vignette so the stage edges fall away.
    var vg = ctx.createRadialGradient(
      w * 0.5, h * 0.5, Math.min(w, h) * 0.34,
      w * 0.5, h * 0.5, Math.max(w, h) * 0.85
    );
    vg.addColorStop(0, 'rgba(0,0,0,0)');
    vg.addColorStop(1, 'rgba(0,0,0,0.42)');
    ctx.fillStyle = vg;
    ctx.fillRect(0, 0, w, h);

    return true;
  }

  /* ---------------------------------------------------------------------- */
  /* Presets — real GlassSpec values, not invented numbers                   */
  /* ---------------------------------------------------------------------- */

  var PRESETS = {
    clear: {
      refraction: 1.0, dispersion: 0.0, tint: 0.04, edge: 0.1,
      rim: 'toric', exponent: 8, sat: 1.0, contrast: 1.0, bezel: 0.0,
      centre: 0.6, rimW: 0.4, frost: 0.16, tier: 'high'
    },
    light: {
      refraction: 1.2, dispersion: 0.06, tint: 0.16, edge: 0.3,
      rim: 'toric', exponent: 8, sat: 1.1, contrast: 1.0, bezel: 0.08,
      centre: 0.05, rimW: 0.55, frost: 0.22, tier: 'high'
    },
    dark: {
      refraction: 1.3, dispersion: 0.09, tint: 0.08, edge: 0.38,
      rim: 'toric', exponent: 8, sat: 1.15, contrast: 1.05, bezel: 0.16,
      centre: 0.05, rimW: 0.6, frost: 0.3, tier: 'high'
    },
    droplet: {
      refraction: 1.35, dispersion: 0.1, tint: 0.12, edge: 0.32,
      rim: 'droplet', exponent: 6, sat: 1.15, contrast: 1.02, bezel: 0.1,
      centre: 0.05, rimW: 0.6, frost: 0.2, tier: 'high'
    }
  };

  var RIM_INDEX = { toric: 0, smooth: 1, droplet: 2, legacy: 3 };

  // Stated wherever the WebGL tier is live, so the page never implies that the
  // browser demo is the Android surface.
  var WEBGL_NOTE =
    'The library\u2019s own shader maths \u2014 SDF, rim profiles, dispersion and ' +
    'dual-lobe lighting \u2014 transcribed to GLSL and running live. Same algorithm, ' +
    'not the Android AGSL runtime.';

  /* ---------------------------------------------------------------------- */
  /* Module                                                                  */
  /* ---------------------------------------------------------------------- */

  function init() {
    var stage = document.getElementById('demo-stage');
    if (!stage) return;

    var canvas = document.getElementById('demo-canvas');
    var panel = document.getElementById('demo-panel');
    var note = document.getElementById('demo-note');
    var status = document.getElementById('demo-status');
    if (!canvas || !panel || !note) return;

    var state = {
      preset: 'light',
      rim: 'toric',
      refraction: PRESETS.light.refraction,
      dispersion: PRESETS.light.dispersion,
      tint: PRESETS.light.tint,
      edge: PRESETS.light.edge,
      exponent: PRESETS.light.exponent,
      sat: PRESETS.light.sat,
      contrast: PRESETS.light.contrast,
      bezel: PRESETS.light.bezel,
      centre: PRESETS.light.centre,
      rimW: PRESETS.light.rimW,
      frost: PRESETS.light.frost,
      tier: 'high',
      light: { x: -0.6, y: -0.8 },
      targetLight: { x: -0.6, y: -0.8 },
      renderScale: 1
    };

    var gl = null;
    var program = null;
    var uniforms = {};
    var backdropTex = null;
    var backdropCanvas = document.createElement('canvas');
    var rafId = 0;
    var needsRender = true;
    var visible = true;
    var intervals = [];
    var lastTickAt = 0;
    var backdropDirty = true;

    /* ---- WebGL bootstrap ------------------------------------------------ */

    function compile(type, src) {
      var shader = gl.createShader(type);
      gl.shaderSource(shader, src);
      gl.compileShader(shader);
      if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
        var log = gl.getShaderInfoLog(shader);
        gl.deleteShader(shader);
        throw new Error('shader compile failed: ' + log);
      }
      return shader;
    }

    function getGL() {
      var attrs = {
        alpha: false,
        antialias: false,
        depth: false,
        stencil: false,
        preserveDrawingBuffer: false,
        desynchronized: false,
        powerPreference: 'high-performance'
      };
      return (
        canvas.getContext('webgl', attrs) ||
        canvas.getContext('experimental-webgl', attrs)
      );
    }

    function initGL() {
      gl = getGL();
      if (!gl) return false;

      program = gl.createProgram();
      gl.attachShader(program, compile(gl.VERTEX_SHADER, VERTEX_SRC));
      gl.attachShader(program, compile(gl.FRAGMENT_SHADER, FRAGMENT_SRC));
      gl.linkProgram(program);
      if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
        throw new Error('program link failed: ' + gl.getProgramInfoLog(program));
      }
      gl.useProgram(program);

      var buffer = gl.createBuffer();
      gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
      gl.bufferData(
        gl.ARRAY_BUFFER,
        new Float32Array([-1, -1, 3, -1, -1, 3]),
        gl.STATIC_DRAW
      );
      var aPos = gl.getAttribLocation(program, 'aPos');
      gl.enableVertexAttribArray(aPos);
      gl.vertexAttribPointer(aPos, 2, gl.FLOAT, false, 0, 0);

      [
        'uResolution', 'uCenter', 'uHalfSize', 'uCornerRadius', 'uRimWidth',
        'uBackdrop', 'uRefraction', 'uDispersion', 'uExponent', 'uRimProfile',
        'uEdgeLight', 'uTint', 'uSaturation', 'uContrast', 'uBezelAlpha',
        'uBezelPx', 'uFrost', 'uCentreW', 'uRimW', 'uLight'
      ].forEach(function (name) {
        uniforms[name] = gl.getUniformLocation(program, name);
      });

      backdropTex = gl.createTexture();
      gl.bindTexture(gl.TEXTURE_2D, backdropTex);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);

      canvas.addEventListener('webglcontextlost', function (event) {
        event.preventDefault();
        gl = null;
        program = null;
        backdropTex = null;
        enableCssFallback(
          'The WebGL context was lost, so the panel has fallen back to the CSS ' +
            'approximation. It will return automatically if the browser restores it.'
        );
      });

      // A lost context is usually transient (GPU reset, tab evicted, driver
      // restart). Rebuild rather than leaving the stage permanently degraded.
      canvas.addEventListener('webglcontextrestored', function () {
        try {
          if (!initGL()) return;
          stage.classList.remove('demo--css');
          stage.classList.add('demo--webgl');
          canvas.removeAttribute('hidden');
          reportQuality();
          note.textContent = WEBGL_NOTE;
          backdropDirty = true;
          resize();
          requestRender();
        } catch (error) {
          if (window.console && console.warn) console.warn('[lumen-demo]', error);
        }
      });

      return true;
    }

    /* ---- Sizing --------------------------------------------------------- */

    function maxDpr() {
      return coarsePointer ? 1.75 : 2;
    }

    function resize() {
      var rect = stage.getBoundingClientRect();
      if (!rect.width || !rect.height) return;

      var dpr = Math.min(window.devicePixelRatio || 1, maxDpr()) * state.renderScale;
      var w = Math.max(1, Math.round(rect.width * dpr));
      var h = Math.max(1, Math.round(rect.height * dpr));

      if (canvas.width === w && canvas.height === h) return;
      canvas.width = w;
      canvas.height = h;

      // The backdrop texture represents the whole stage; keep it near 1:1 with
      // the drawing buffer but bounded so a 4K desktop does not allocate a huge
      // canvas.
      var bMax = coarsePointer ? 1400 : 1800;
      var bScale = Math.min(1, bMax / Math.max(w, h));
      backdropCanvas.width = Math.max(1, Math.round(w * bScale));
      backdropCanvas.height = Math.max(1, Math.round(h * bScale));

      backdropDirty = true;
      needsRender = true;
    }

    // The backdrop is static, so it is rasterised and uploaded once per size
    // change rather than every frame. Re-uploading a full texture per frame was
    // measurably expensive and could starve the GPU on software renderers.
    function uploadBackdrop() {
      if (!gl || !backdropTex) return;
      if (!backdropDirty) return;
      if (!drawBackdrop(backdropCanvas)) return;
      gl.bindTexture(gl.TEXTURE_2D, backdropTex);
      gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false);
      gl.texImage2D(
        gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, backdropCanvas
      );
      backdropDirty = false;
    }

    /* ---- Render --------------------------------------------------------- */

    function render() {
      if (!gl || !program) return;

      var w = canvas.width;
      var h = canvas.height;
      gl.viewport(0, 0, w, h);

      var stageRect = stage.getBoundingClientRect();
      var panelRect = panel.getBoundingClientRect();
      var sx = w / stageRect.width;
      var sy = h / stageRect.height;

      var cx = (panelRect.left - stageRect.left + panelRect.width * 0.5) * sx;
      var cy = (panelRect.top - stageRect.top + panelRect.height * 0.5) * sy;
      var halfW = panelRect.width * 0.5 * sx;
      var halfH = panelRect.height * 0.5 * sy;

      // Match the DOM's own corner radius so the shader and the CSS agree.
      var cssRadius = parseFloat(
        window.getComputedStyle(panel).borderTopLeftRadius
      ) || 22;
      var radiusPx = cssRadius * Math.min(sx, sy);
      var high = state.tier === 'high';

      gl.uniform2f(uniforms.uResolution, w, h);
      gl.uniform2f(uniforms.uCenter, cx, cy);
      gl.uniform2f(uniforms.uHalfSize, halfW, halfH);
      gl.uniform1f(uniforms.uCornerRadius, radiusPx);
      gl.uniform1f(uniforms.uRimWidth, Math.min(halfW, halfH) * 0.42);

      gl.uniform1f(uniforms.uRefraction, state.refraction);
      gl.uniform1f(uniforms.uDispersion, high ? state.dispersion : 0.0);
      gl.uniform1f(uniforms.uExponent, state.exponent);
      gl.uniform1f(uniforms.uRimProfile, RIM_INDEX[state.rim] || 0);
      gl.uniform1f(uniforms.uEdgeLight, state.edge);
      gl.uniform1f(uniforms.uTint, state.tint);
      gl.uniform1f(uniforms.uSaturation, state.sat);
      gl.uniform1f(uniforms.uContrast, state.contrast);
      gl.uniform1f(uniforms.uBezelAlpha, high ? state.bezel : 0.0);
      gl.uniform1f(uniforms.uBezelPx, radiusPx * 0.45);
      gl.uniform1f(uniforms.uFrost, state.frost);
      gl.uniform1f(uniforms.uCentreW, state.centre);
      gl.uniform1f(uniforms.uRimW, state.rimW);
      gl.uniform2f(uniforms.uLight, state.light.x, state.light.y);

      gl.activeTexture(gl.TEXTURE0);
      gl.bindTexture(gl.TEXTURE_2D, backdropTex);
      gl.uniform1i(uniforms.uBackdrop, 0);

      gl.drawArrays(gl.TRIANGLES, 0, 3);
    }

    // Frame pacing is the honest signal: it includes GPU work, compositing and
    // everything else the browser must do, unlike timing the submit call.
    function adaptQuality() {
      if (intervals.length < 24) return;
      var sorted = intervals.slice().sort(function (a, b) { return a - b; });
      var median = sorted[Math.floor(sorted.length / 2)];
      var next = state.renderScale;

      if (median > 24 && state.renderScale > 0.6) {
        next = Math.max(0.6, state.renderScale - 0.2);
      } else if (median < 15 && state.renderScale < 1) {
        next = Math.min(1, state.renderScale + 0.2);
      } else {
        return;
      }

      state.renderScale = next;
      intervals.length = 0;
      resize();
      // resize() clears the drawing buffer, so something must redraw it.
      requestRender();
      reportQuality();
    }

    function medianInterval() {
      if (!intervals.length) return 0;
      var sorted = intervals.slice().sort(function (a, b) { return a - b; });
      return sorted[Math.floor(sorted.length / 2)];
    }

    function reportQuality() {
      if (!status) return;
      var pct = Math.round(state.renderScale * 100);
      status.textContent =
        pct >= 100 ? 'WebGL · full resolution'
                   : 'WebGL · ' + pct + '% render scale (adaptive)';
    }

    /* ---- Animation ------------------------------------------------------ */

    function tick() {
      rafId = 0;

      var now = window.performance ? performance.now() : 0;
      if (lastTickAt) intervals.push(now - lastTickAt);
      lastTickAt = now;
      if (intervals.length > 40) intervals.shift();

      var dx = state.targetLight.x - state.light.x;
      var dy = state.targetLight.y - state.light.y;
      var moving = Math.abs(dx) > 0.003 || Math.abs(dy) > 0.003;

      if (moving) {
        state.light.x += dx * 0.2;
        state.light.y += dy * 0.2;
        needsRender = true;
      } else {
        state.light.x = state.targetLight.x;
        state.light.y = state.targetLight.y;
      }

      if (needsRender) {
        uploadBackdrop();
        render();
        needsRender = false;
        if (moving) adaptQuality();
      }

      // Keep easing only while the light is still travelling, then stop: an idle
      // stage does no work at all.
      if (moving) schedule();
    }

    function schedule() {
      if (rafId || !visible || document.hidden) return;
      rafId = window.requestAnimationFrame(tick);
    }

    function requestRender() {
      needsRender = true;
      schedule();
    }

    /* ---- Pointer -------------------------------------------------------- */

    function setLightFromPoint(clientX, clientY) {
      var rect = stage.getBoundingClientRect();
      var x = (clientX - rect.left) / rect.width;
      var y = (clientY - rect.top) / rect.height;

      var panelRect = panel.getBoundingClientRect();
      var cx = (panelRect.left - rect.left + panelRect.width * 0.5) / rect.width;
      var cy = (panelRect.top - rect.top + panelRect.height * 0.5) / rect.height;

      // To-light vector from the glass centre toward the pointer, matching the
      // shader's uLight semantics.
      var lx = x - cx;
      var ly = y - cy;
      var len = Math.sqrt(lx * lx + ly * ly) || 1;
      state.targetLight.x = lx / len;
      state.targetLight.y = ly / len;

      // Cheap CSS layer: glow position and panel tilt, no shader work of its own.
      stage.style.setProperty('--light-x', (x * 100).toFixed(1) + '%');
      stage.style.setProperty('--light-y', (y * 100).toFixed(1) + '%');

      if (!reduceMotion.matches) {
        panel.style.setProperty('--tilt-y', ((x - 0.5) * 2.2).toFixed(2) + 'deg');
        panel.style.setProperty('--tilt-x', (-(y - 0.5) * 2.2).toFixed(2) + 'deg');
      }

      requestRender();
    }

    function onPointerMove(event) {
      setLightFromPoint(event.clientX, event.clientY);
    }

    function onPointerLeave() {
      // Ease back to the shader's real default: light from the top-left.
      state.targetLight.x = -0.6;
      state.targetLight.y = -0.8;
      panel.style.setProperty('--tilt-x', '0deg');
      panel.style.setProperty('--tilt-y', '0deg');
      requestRender();
    }

    /* ---- Controls ------------------------------------------------------- */

    function setPill(group, value) {
      Array.prototype.forEach.call(
        document.querySelectorAll('[data-' + group + ']'),
        function (pill) {
          pill.setAttribute(
            'aria-pressed',
            pill.getAttribute('data-' + group) === value ? 'true' : 'false'
          );
        }
      );
    }

    function setRange(name, value) {
      var input = document.querySelector('[data-demo="' + name + '"]');
      if (input) input.value = String(Math.round(value));
    }

    function writeOutputs() {
      var map = {
        refraction: state.refraction.toFixed(2),
        dispersion: state.dispersion.toFixed(2),
        tint: state.tint.toFixed(2),
        edge: state.edge.toFixed(2)
      };
      Object.keys(map).forEach(function (name) {
        var out = document.getElementById('out-' + name);
        if (out) out.textContent = map[name];
      });
    }

    function syncControls() {
      setRange('refraction', state.refraction * 100);
      setRange('dispersion', state.dispersion * 100);
      setRange('tint', state.tint * 100);
      setRange('edge', state.edge * 100);
      setPill('demo-preset', state.preset);
      setPill('demo-rim', state.rim);
      setPill('demo-tier', state.tier);
      writeOutputs();
    }

    function applyPreset(name) {
      var preset = PRESETS[name];
      if (!preset) return;
      state.preset = name;
      state.rim = preset.rim;
      state.refraction = preset.refraction;
      state.dispersion = preset.dispersion;
      state.tint = preset.tint;
      state.edge = preset.edge;
      state.exponent = preset.exponent;
      state.sat = preset.sat;
      state.contrast = preset.contrast;
      state.bezel = preset.bezel;
      state.centre = preset.centre;
      state.rimW = preset.rimW;
      state.frost = preset.frost;
      state.tier = preset.tier;
      syncControls();
      requestRender();
    }

    function bindControls() {
      Array.prototype.forEach.call(
        document.querySelectorAll('[data-demo-preset]'),
        function (pill) {
          pill.addEventListener('click', function () {
            applyPreset(pill.getAttribute('data-demo-preset'));
          });
        }
      );

      Array.prototype.forEach.call(
        document.querySelectorAll('[data-demo-rim]'),
        function (pill) {
          pill.addEventListener('click', function () {
            state.rim = pill.getAttribute('data-demo-rim');
            setPill('demo-rim', state.rim);
            requestRender();
          });
        }
      );

      Array.prototype.forEach.call(
        document.querySelectorAll('[data-demo-tier]'),
        function (pill) {
          pill.addEventListener('click', function () {
            state.tier = pill.getAttribute('data-demo-tier');
            setPill('demo-tier', state.tier);
            requestRender();
          });
        }
      );

      var handlers = {
        refraction: function (v) { state.refraction = v / 100; },
        dispersion: function (v) { state.dispersion = v / 100; },
        tint: function (v) { state.tint = v / 100; },
        edge: function (v) { state.edge = v / 100; }
      };

      Array.prototype.forEach.call(
        document.querySelectorAll('[data-demo]'),
        function (input) {
          input.addEventListener('input', function () {
            var handler = handlers[input.getAttribute('data-demo')];
            if (handler) handler(Number(input.value));
            writeOutputs();
            requestRender();
          });
        }
      );
    }

    /* ---- Fallback ------------------------------------------------------- */

    function enableCssFallback(message) {
      stage.classList.remove('demo--webgl');
      stage.classList.add('demo--css');
      canvas.setAttribute('hidden', '');
      if (status) status.textContent = 'CSS fallback';
      note.textContent = message;
    }

    /* ---- Boot ----------------------------------------------------------- */

    var booted = false;
    try {
      booted = initGL();
    } catch (error) {
      booted = false;
      if (window.console && console.warn) console.warn('[lumen-demo]', error);
    }

    if (!booted) {
      enableCssFallback(
        'WebGL could not start in this browser, so the panel is using the CSS ' +
          'approximation. On Android the real surface is GPU-rendered by an AGSL shader.'
      );
      return;
    }

    stage.classList.add('demo--webgl');
    note.textContent = WEBGL_NOTE;
    reportQuality();

    resize();
    uploadBackdrop();
    bindControls();
    syncControls();
    requestRender();

    stage.addEventListener('pointermove', onPointerMove, { passive: true });
    stage.addEventListener('pointerleave', onPointerLeave, { passive: true });

    if (coarsePointer) {
      // Tap moves the light; never trap the scroll gesture.
      stage.addEventListener(
        'pointerdown',
        function (event) {
          if (event.pointerType === 'touch') setLightFromPoint(event.clientX, event.clientY);
        },
        { passive: true }
      );
    }

    if ('ResizeObserver' in window) {
      new ResizeObserver(function () {
        resize();
        requestRender();
      }).observe(stage);
    } else {
      window.addEventListener('resize', function () {
        resize();
        requestRender();
      });
    }

    if ('IntersectionObserver' in window) {
      new IntersectionObserver(
        function (entries) {
          entries.forEach(function (entry) {
            visible = entry.isIntersecting;
            if (visible) requestRender();
          });
        },
        { rootMargin: '120px' }
      ).observe(stage);
    }

    document.addEventListener('visibilitychange', function () {
      if (!document.hidden) requestRender();
    });

    // Small surface for the test suite and for debugging.
    //
    // snapshot() renders and then reads the framebuffer in the same task, so it
    // returns deterministic pixels. Reading a canvas that uses
    // preserveDrawingBuffer:false from outside a frame is not reliable, and the
    // suite compares frames to prove the shader responds to input.
    function snapshot(cols, rows) {
      if (!gl || !program) return null;
      uploadBackdrop();
      render();

      cols = cols || 16;
      rows = rows || 10;
      var w = canvas.width;
      var h = canvas.height;
      var buf = new Uint8Array(w * h * 4);
      gl.readPixels(0, 0, w, h, gl.RGBA, gl.UNSIGNED_BYTE, buf);

      var grid = [];
      for (var ry = 0; ry < rows; ry++) {
        for (var rx = 0; rx < cols; rx++) {
          var x0 = Math.floor((rx * w) / cols);
          var x1 = Math.floor(((rx + 1) * w) / cols);
          var y0 = Math.floor((ry * h) / rows);
          var y1 = Math.floor(((ry + 1) * h) / rows);
          var total = 0;
          var count = 0;
          for (var y = y0; y < y1; y += 2) {
            for (var x = x0; x < x1; x += 2) {
              var i = (y * w + x) * 4;
              total += buf[i] * 0.2126 + buf[i + 1] * 0.7152 + buf[i + 2] * 0.0722;
              count++;
            }
          }
          grid.push(count ? total / count : 0);
        }
      }
      return grid;
    }

    window.lumenDemo = {
      tier: 'webgl',
      state: state,
      presets: PRESETS,
      applyPreset: applyPreset,
      requestRender: requestRender,
      setLightFromPoint: setLightFromPoint,
      snapshot: snapshot,
      getStats: function () {
        var median = medianInterval();
        return {
          frameIntervalMs: median,
          approxFps: median > 0 ? Math.round(1000 / median) : 0,
          renderScale: state.renderScale
        };
      },
      getRenderScale: function () { return state.renderScale; }
    };
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
