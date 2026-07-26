
//   node scripts/update-version.js 2.1.0
//   node scripts/update-version.js 2.1.0 15


const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');

// ---------- 文件路径 ----------
const VERSION_JSON = path.join(ROOT, 'scripts', 'version.json');
const PACKAGE_JSON = path.join(ROOT, 'package.json');
const PACKAGE_LOCK_JSON = path.join(ROOT, 'package-lock.json');
const BUILD_GRADLE = path.join(ROOT, 'android', 'app', 'build.gradle');

// ---------- 读取函数 ----------
function readJSON(filePath) {
  return JSON.parse(fs.readFileSync(filePath, 'utf-8'));
}

function writeJSON(filePath, data) {
  fs.writeFileSync(filePath, JSON.stringify(data, null, 2) + '\n', 'utf-8');
}

// ---------- 当前版本信息 ----------
function getCurrentVersion() {
  const versionData = readJSON(VERSION_JSON);
  const pkg = readJSON(PACKAGE_JSON);
  const lockPkg = readJSON(PACKAGE_LOCK_JSON);

  // 从 build.gradle 中提取 versionCode 和 appVersion 默认值
  const gradleContent = fs.readFileSync(BUILD_GRADLE, 'utf-8');
  const versionCodeMatch = gradleContent.match(/versionCode\s+(\d+)/);
  const versionCode = versionCodeMatch ? parseInt(versionCodeMatch[1], 10) : null;
  const appVersionMatch = gradleContent.match(/def appVersion = "([^"]+)"/);
  const gradleDefaultVersion = appVersionMatch ? appVersionMatch[1] : null;

  return {
    versionName: versionData.version,
    pkgVersion: pkg.version,
    lockPkgVersion: lockPkg.version,
    lockPackageVersion: lockPkg.packages?.[""]?.version,
    versionCode,
    gradleDefaultVersion,
  };
}

// ---------- 校验 ----------
function isValidVersion(version) {
  return /^\d+\.\d+\.\d+$/.test(version);
}

// ---------- 更新函数 ----------

function updateVersionJson(newVersion) {
  const data = readJSON(VERSION_JSON);
  data.version = newVersion;
  writeJSON(VERSION_JSON, data);
  console.log(`  ✔ scripts/version.json       → version: "${newVersion}"`);
}

function updatePackageJson(newVersion) {
  const data = readJSON(PACKAGE_JSON);
  data.version = newVersion;
  writeJSON(PACKAGE_JSON, data);
  console.log(`  ✔ package.json               → version: "${newVersion}"`);
}

function updatePackageLockJson(newVersion) {
  const data = readJSON(PACKAGE_LOCK_JSON);
  // 更新顶层 version
  data.version = newVersion;
  // 更新 packages[""] 中的 version
  if (data.packages && data.packages[""]) {
    data.packages[""].version = newVersion;
  }
  writeJSON(PACKAGE_LOCK_JSON, data);
  console.log(`  ✔ package-lock.json          → version: "${newVersion}"`);
}

function updateGradleDefaultVersion(newVersion) {
  let content = fs.readFileSync(BUILD_GRADLE, 'utf-8');
  const replaced = content.replace(
    /(def appVersion = ")[^"]*(")/,
    (match, prefix, suffix) => `${prefix}${newVersion}${suffix}`
  );
  if (replaced !== content) {
    fs.writeFileSync(BUILD_GRADLE, replaced, 'utf-8');
    console.log(`  ✔ android/app/build.gradle   → appVersion: "${newVersion}"`);
  }
}

function updateVersionCode(newCode) {
  let content = fs.readFileSync(BUILD_GRADLE, 'utf-8');
  const replaced = content.replace(/(versionCode\s+)(\d+)/, (match, prefix, oldCode) => {
    console.log(`  ✔ android/app/build.gradle → versionCode: ${oldCode} → ${newCode}`);
    return `${prefix}${newCode}`;
  });
  if (replaced === content) {
    console.warn('  ⚠ android/app/build.gradle 中未找到 versionCode，跳过');
    return;
  }
  fs.writeFileSync(BUILD_GRADLE, replaced, 'utf-8');
}

// ---------- 主流程 ----------
function main() {
  const args = process.argv.slice(2);

  // 查看当前版本
  if (args.length === 0 || args[0] === '--show') {
    const cur = getCurrentVersion();
    console.log('\n当前版本信息：');
    console.log(`  versionName       (version.json)     : ${cur.versionName}`);
    console.log(`  pkg version       (package.json)     : ${cur.pkgVersion}`);
    console.log(`  lock version      (package-lock.json) : ${cur.lockPkgVersion}`);
    console.log(`  lock package ver  (package-lock.json) : ${cur.lockPackageVersion}`);
    console.log(`  appVersion 默认值  (build.gradle)     : ${cur.gradleDefaultVersion}`);
    console.log(`  versionCode       (build.gradle)     : ${cur.versionCode}`);
    console.log();
    return;
  }

  const newVersion = args[0];

  if (!isValidVersion(newVersion)) {
    console.error(`\n❌ 无效的版本号: "${newVersion}"`);
    console.error('   版本号格式应为 x.y.z，例如 2.1.0\n');
    process.exit(1);
  }

  // 可选参数：versionCode
  const newCode = args[1];
  if (newCode !== undefined && !/^\d+$/.test(newCode)) {
    console.error(`\n❌ 无效的 versionCode: "${newCode}"，必须为正整数\n`);
    process.exit(1);
  }

  console.log(`\n开始更新版本号为: ${newVersion}${newCode ? `, versionCode: ${newCode}` : ''}\n`);

  try {
    updateVersionJson(newVersion);
    updatePackageJson(newVersion);
    updatePackageLockJson(newVersion);
    updateGradleDefaultVersion(newVersion);
    if (newCode !== undefined) {
      updateVersionCode(parseInt(newCode, 10));
    }
    console.log('\n✅ 版本号更新完成\n');
  } catch (err) {
    console.error('\n❌ 更新失败:', err.message, '\n');
    process.exit(1);
  }
}

main();
