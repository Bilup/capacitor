const fs = require('fs');
const path = require('path');

const projectRoot = path.join(__dirname, '..');

const versionJsonPath = path.join(projectRoot, 'version.json');
const packageJsonPath = path.join(projectRoot, 'package.json');
const packageLockJsonPath = path.join(projectRoot, 'package-lock.json');
const buildGradlePath = path.join(projectRoot, 'android', 'app', 'build.gradle');
const outputMetadataPath = path.join(projectRoot, 'android', 'app', 'release', 'output-metadata.json');

function readVersion() {
    const content = fs.readFileSync(versionJsonPath, 'utf-8');
    const versionData = JSON.parse(content);
    return versionData.version;
}

function updatePackageJson(version) {
    const content = fs.readFileSync(packageJsonPath, 'utf-8');
    const data = JSON.parse(content);
    data.version = version;
    fs.writeFileSync(packageJsonPath, JSON.stringify(data, null, 2) + '\n');
    console.log('Updated package.json version to', version);
}

function updatePackageLockJson(version) {
    const content = fs.readFileSync(packageLockJsonPath, 'utf-8');
    const data = JSON.parse(content);
    data.version = version;
    if (data.packages && data.packages['']) {
        data.packages[''].version = version;
    }
    fs.writeFileSync(packageLockJsonPath, JSON.stringify(data, null, 2) + '\n');
    console.log('Updated package-lock.json version to', version);
}

function updateBuildGradle(version) {
    let content = fs.readFileSync(buildGradlePath, 'utf-8');
    content = content.replace(/versionName\s+"[^"]+"/, `versionName "${version}"`);
    fs.writeFileSync(buildGradlePath, content);
    console.log('Updated build.gradle versionName to', version);
}

function updateOutputMetadata(version) {
    if (!fs.existsSync(outputMetadataPath)) {
        console.log('output-metadata.json not found, skipping');
        return;
    }
    const content = fs.readFileSync(outputMetadataPath, 'utf-8');
    const data = JSON.parse(content);
    if (data.elements) {
        data.elements.forEach(element => {
            if (element.versionName !== undefined) {
                element.versionName = version;
            }
        });
    }
    fs.writeFileSync(outputMetadataPath, JSON.stringify(data, null, 2) + '\n');
    console.log('Updated output-metadata.json versionName to', version);
}

function main() {
    try {
        const version = readVersion();
        console.log('Reading version from version.json:', version);
        
        updatePackageJson(version);
        updatePackageLockJson(version);
        updateBuildGradle(version);
        updateOutputMetadata(version);
        
        console.log('\nAll files updated successfully!');
    } catch (error) {
        console.error('Error updating version:', error.message);
        process.exit(1);
    }
}

main();