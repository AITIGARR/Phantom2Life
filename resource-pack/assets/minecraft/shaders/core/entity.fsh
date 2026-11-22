#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
#ifdef PER_FACE_LIGHTING
in vec4 vertexPerFaceColorBack;
in vec4 vertexPerFaceColorFront;
#else
in vec4 vertexColor;
#endif
in vec4 lightMapColor;
in vec4 overlayColor;
in vec2 texCoord0;

out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord0);

    // Phantom2Life: discard the unique transparent sign atlas marker even
    // when a third-party renderer does not honour ALPHA_CUTOUT.
    const vec4 phantomSignMarker = vec4(17.0/255.0, 29.0/255.0, 41.0/255.0, 15.0/255.0);
    // Some renderers/atlas pipelines lose or normalize the texture alpha.
    // Match this sign-only RGB marker independently of alpha. This is a
    // temporary client compatibility workaround, not a new block renderer.
    vec3 phantomSignRGB = phantomSignMarker.rgb;
    if (distance(color.rgb, phantomSignRGB) < 0.095) {
        discard;
    }
    // Also match color if the atlas uses premultiplied alpha.
    if (color.a > 0.0 && color.a < 0.20
        && distance(color.rgb / max(color.a, 0.001), phantomSignRGB) < 0.095) {
        discard;
    }
#ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
#endif
#ifdef PER_FACE_LIGHTING
    color *= (gl_FrontFacing ? vertexPerFaceColorFront : vertexPerFaceColorBack) * ColorModulator;
#else
    color *= vertexColor * ColorModulator;
#endif
#ifndef NO_OVERLAY
    color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
#endif
#ifndef EMISSIVE
    color *= lightMapColor;
#endif
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
