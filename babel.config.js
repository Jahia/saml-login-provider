module.exports = {
    presets: [
        ['@babel/preset-env', {targets: {chrome: '60', edge: '44', firefox: '54', safari: '12'}}],
        '@babel/preset-react'
    ],
    plugins: [
        '@babel/plugin-syntax-dynamic-import'
    ]
};
