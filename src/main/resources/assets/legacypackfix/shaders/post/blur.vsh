#version 330
#extension GL_ARB_separate_shader_objects : require

// Legacy Pack Fix: the minecraft:post/blur vertex shader of 1.21.6 - 1.21.8, which 26.3 no longer has, for post
// effects of older packs that still name it. Positions the pass like minecraft:core/screenquad and hands the
// fragment shader the blur step the old one did.

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform BlurConfig {
    vec2 BlurDir;
    float Radius;
};

layout(location = 0) out vec2 texCoord;
layout(location = 1) out vec2 sampleStep;

void main() {
    vec2 uv = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    vec4 pos = vec4(uv * vec2(2, 2) + vec2(-1, -1), 0, 1);

    gl_Position = pos;
    texCoord = uv;

    vec2 oneTexel = 1.0 / InSize;
    sampleStep = oneTexel * BlurDir;
}
