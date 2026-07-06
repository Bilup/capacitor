const fs = require('fs');
const path = require('path');

const guiBuildDir = path.join(__dirname, '../scratch-gui/build');

const jsFiles = fs.readdirSync(guiBuildDir + '/static/js').filter(f => f.startsWith('main.'));
if (jsFiles.length === 0) {
    console.error('main JS file not found');
    process.exit(1);
}

const mainJsFile = path.join(guiBuildDir, 'static/js', jsFiles[0]);
let jsContent = fs.readFileSync(mainJsFile, 'utf8');

const patches = [
    ['isScratchDesktop:false', 'isScratchDesktop:true'],
    ['isScratchDesktop: !0', 'isScratchDesktop: !1'],
    ['isScratchDesktop:!0', 'isScratchDesktop:!1'],
    ['isScratchDesktop:false,', 'isScratchDesktop:true,'],
    ['isScratchDesktop: !0,', 'isScratchDesktop: !1,'],
];

let patched = false;
for (const [oldStr, newStr] of patches) {
    if (jsContent.includes(oldStr)) {
        jsContent = jsContent.replace(oldStr, newStr);
        patched = true;
        console.log(`Patched: ${oldStr} -> ${newStr}`);
    }
}

if (!patched) {
    console.log('No direct patch found, trying alternative method');
    
    const htmlFile = path.join(guiBuildDir, 'index.html');
    let html = fs.readFileSync(htmlFile, 'utf8');
    
    const bodyEnd = html.lastIndexOf('</body>');
    if (bodyEnd !== -1) {
        const patchScript = `
<script>
    window.__scratchDesktopPatch = true;
    document.addEventListener('DOMContentLoaded', function() {
        const scripts = document.querySelectorAll('script[src*="main."]');
        scripts.forEach(script => {
            const originalSrc = script.src;
            const newScript = document.createElement('script');
            newScript.src = originalSrc;
            newScript.onload = function() {
                if (window.Scratch && window.Scratch.render) {
                    const originalRender = window.Scratch.render;
                    window.Scratch.render = function(container, props) {
                        props = props || {};
                        props.isScratchDesktop = true;
                        props.isFullScreen = false;
                        props.canEditTitle = true;
                        props.canModifyCloudData = false;
                        props.canUseCloud = false;
                        props.backpackVisible = true;
                        props.backpackHost = '_local_';
                        return originalRender.call(this, container, props);
                    };
                }
            };
            script.parentNode.replaceChild(newScript, script);
        });
    });
</script>`;
        html = html.slice(0, bodyEnd) + patchScript + html.slice(bodyEnd);
        fs.writeFileSync(htmlFile, html, 'utf8');
        console.log('Patched index.html with DOMContentLoaded hook');
        patched = true;
    }
}

if (patched) {
    fs.writeFileSync(mainJsFile, jsContent, 'utf8');
    console.log('Patch complete');
} else {
    console.error('Failed to patch scratch-gui');
    process.exit(1);
}