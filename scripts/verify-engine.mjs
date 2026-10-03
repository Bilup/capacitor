#!/usr/bin/env node
/**
 * Verify that the kernel this build ships is the kernel it is supposed to ship.
 *
 * The Android app carries no engine code of its own: scratch-gui's
 * pnpm-lock.yaml decides which scratch-vm / scratch-render build gets compiled
 * in. That indirection is why the 2.0.2 APK shipped a kernel that predated the
 * Android renderer OOM fixes while the pipeline stayed green -- nothing ever
 * looked at what was actually inside the artifact.
 *
 * Two modes, both driven by engine-lock.json:
 *
 *   lock [--gui <dir>]
 *       Parse scratch-gui/pnpm-lock.yaml and assert the scratch-vm /
 *       scratch-render tarball SHAs equal requiredEngines. Takes about a
 *       second, so it runs before anything is compiled.
 *
 *   bundle <path...>
 *       Read the compiled bundle out of a build directory or an .apk and assert
 *       every requiredBundleMarkers string is present in the renderer chunk.
 *       This is the ground truth: it proves what was compiled in, no matter how
 *       dependency resolution went. With no path it checks scratch-gui/build
 *       and any APK under android/app/build/outputs/apk.
 *
 * Exit code is non-zero on any mismatch, so a stale kernel fails the build
 * instead of reaching users.
 */

import {spawnSync} from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath, pathToFileURL} from 'node:url';

import {normalisePath} from './lib/win-path.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const LOCK_PATH = path.join(ROOT, 'engine-lock.json');
const CHUNK_HINT = 'scratch-engine';
/** A minified string long enough to survive bundling, used to locate the renderer chunk. */
const RENDERER_LOCATOR = 'createSVGSkin';
const MAX_BUFFER = 128 * 1024 * 1024;

const failures = [];
const warnings = [];

const fail = message => failures.push(message);
const warn = message => warnings.push(message);

const sh = (file, args) => spawnSync(file, args, {
    encoding: 'utf8',
    maxBuffer: MAX_BUFFER
});

const readJson = file => {
    try {
        return JSON.parse(fs.readFileSync(file, 'utf8'));
    } catch (e) {
        console.error(`[verify-engine] cannot read ${file}: ${e.message}`);
        process.exit(1);
    }
};

const escapeRe = value => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

const walk = (dir, filter) => {
    const out = [];
    for (const entry of fs.readdirSync(dir, {withFileTypes: true})) {
        const full = path.join(dir, entry.name);
        if (entry.isDirectory()) out.push(...walk(full, filter));
        else if (filter(full)) out.push(full);
    }
    return out;
};

// ---------------------------------------------------------------- lock mode

/**
 * scratch-gui pins the engines as codeload tarballs, e.g.
 *   scratch-vm@https://codeload.github.com/Bilup/scratch-vm/tar.gz/<sha>:
 *
 * Exported so scripts/pin-scratch-gui.mjs reads the lockfile the same way this
 * gate does -- a pin written by a different parser than the one that checks it
 * would be a bug waiting to happen.
 *
 * @param {string} lockText contents of pnpm-lock.yaml
 * @param {string} engine package name, e.g. 'scratch-vm'
 * @returns {string[]} distinct 40-character SHAs the engine resolves to
 */
export const engineShaInLock = (lockText, engine) => {
    const pattern = new RegExp(
        `${escapeRe(engine)}@https://codeload\\.github\\.com/Bilup/${escapeRe(engine)}/tar\\.gz/([0-9a-f]{40})`,
        'g'
    );
    const found = new Set();
    let match;
    while ((match = pattern.exec(lockText)) !== null) found.add(match[1]);
    return [...found];
};

const verifyLock = (lock, guiDir) => {
    const lockFile = path.join(guiDir, 'pnpm-lock.yaml');
    if (!fs.existsSync(lockFile)) {
        fail(`lock mode: ${lockFile} not found. Run scripts/fetch-scratch-gui.mjs first.`);
        return;
    }
    const lockText = fs.readFileSync(lockFile, 'utf8');

    console.log(`[verify-engine] lock mode: ${lockFile}`);
    console.log('');

    const rows = [];
    for (const [engine, requirement] of Object.entries(lock.requiredEngines)) {
        const found = engineShaInLock(lockText, engine);
        const expected = requirement.sha;
        let status;
        if (found.length === 0) {
            status = 'MISSING';
            fail(`lock mode: ${engine} is not pinned in ${lockFile}`);
        } else if (found.length > 1) {
            status = 'AMBIGUOUS';
            fail(`lock mode: ${engine} resolves to ${found.length} different SHAs: ${found.join(', ')}`);
        } else if (found[0] !== expected) {
            status = 'STALE';
            fail(
                `lock mode: ${engine} resolves to ${found[0]}, expected ${expected}. ` +
                `scratch-gui's lockfile is behind the kernel fixes.`
            );
        } else {
            status = 'ok';
        }
        rows.push([engine, (found[0] || '(none)').slice(0, 12), expected.slice(0, 12), status]);
    }

    printTable(['engine', 'resolved', 'required', 'status'], rows);

    // The checkout should be the pin, but a mismatch here is not itself a kernel
    // problem -- the engine SHAs above are what actually decide the build -- so
    // report it without failing.
    const head = sh('git', ['-C', guiDir, 'rev-parse', 'HEAD']);
    if (head.status === 0) {
        const actual = head.stdout.trim();
        if (actual !== lock.scratchGui.sha) {
            warn(
                `checkout HEAD is ${actual.slice(0, 12)} but engine-lock.json pins ` +
                `${lock.scratchGui.sha.slice(0, 12)}. Re-run scripts/fetch-scratch-gui.mjs ` +
                'unless you are deliberately inspecting another revision.'
            );
        }
    }
};

// -------------------------------------------------------------- bundle mode

const readApkEntries = apk => {
    const listing = sh('unzip', ['-Z1', apk]);
    if (listing.error || listing.status !== 0) {
        fail(`bundle mode: cannot list ${apk} (is unzip installed?)`);
        return null;
    }
    return listing.stdout.split('\n').map(line => line.trim()).filter(Boolean);
};

const readApkEntry = (apk, entry) => {
    const result = sh('unzip', ['-p', apk, entry]);
    return result.status === 0 ? result.stdout : null;
};

/**
 * Pull the renderer chunk out of one artifact.
 *
 * Prefers the chunk whose name says what it is; falls back to scanning the JS
 * payload for a symbol only the renderer defines, so a future chunk rename
 * degrades to "slower" rather than "silently passes".
 *
 * @returns {{entry: string, text: string}|null}
 */
const loadBundleFromApk = (apk, note) => {
    const entries = readApkEntries(apk);
    if (!entries) return null;

    const named = entries.filter(name => name.endsWith('.js') && name.includes(CHUNK_HINT));
    for (const entry of named) {
        const text = readApkEntry(apk, entry);
        if (text && text.includes(RENDERER_LOCATOR)) return {entry, text};
    }

    const jsEntries = entries.filter(name => name.endsWith('.js') && name.includes('assets/public/js/'));
    for (const entry of jsEntries) {
        const text = readApkEntry(apk, entry);
        if (text && text.includes(RENDERER_LOCATOR)) {
            note(`chunk name no longer contains "${CHUNK_HINT}"; identified it by "${RENDERER_LOCATOR}" instead`);
            return {entry, text};
        }
    }
    return null;
};

const loadBundleFromDir = (dir, note) => {
    const candidates = walk(dir, file => file.endsWith('.js'));
    const named = candidates.filter(file => file.includes(CHUNK_HINT));
    for (const file of named) {
        const text = fs.readFileSync(file, 'utf8');
        if (text.includes(RENDERER_LOCATOR)) {
            return {entry: path.relative(dir, file), text};
        }
    }
    for (const file of candidates) {
        const text = fs.readFileSync(file, 'utf8');
        if (text.includes(RENDERER_LOCATOR)) {
            note(`chunk name no longer contains "${CHUNK_HINT}"; identified it by "${RENDERER_LOCATOR}" instead`);
            return {entry: path.relative(dir, file), text};
        }
    }
    return null;
};

/**
 * The decode limiter's concurrency is the argument right after the decode
 * callback's body, so it survives minification as `},50)` or `},12)`. Detection
 * is best-effort: a minifier rewrite would hide it, which is a warning, but
 * finding a value that is not the expected one is a hard failure.
 */
const detectDecodeConcurrency = text => {
    const anchor = text.lastIndexOf('createImageBitmap');
    if (anchor === -1) return null;
    const window = text.slice(anchor, anchor + 2000);
    const match = /},(\d+)\)/.exec(window);
    return match ? Number(match[1]) : null;
};

const verifyBundle = (lock, targets) => {
    if (targets.length === 0) {
        fail('bundle mode: no build directory or APK found to inspect');
        return;
    }

    for (const target of targets) {
        const notes = [];
        const note = message => notes.push(message);
        console.log(`[verify-engine] bundle mode: ${target}`);

        let bundle = null;
        if (target.toLowerCase().endsWith('.apk')) {
            bundle = loadBundleFromApk(target, note);
        } else if (fs.existsSync(target) && fs.statSync(target).isDirectory()) {
            bundle = loadBundleFromDir(target, note);
        } else {
            fail(`bundle mode: ${target} is neither an APK nor an existing directory`);
            continue;
        }

        if (!bundle) {
            fail(`bundle mode: no renderer chunk found inside ${target} (looked for "${RENDERER_LOCATOR}")`);
            continue;
        }

        console.log(`  chunk: ${bundle.entry} (${(bundle.text.length / 1024 / 1024).toFixed(2)} MB)`);
        for (const message of notes) console.log(`  note: ${message}`);

        const rows = [];
        for (const requirement of lock.requiredBundleMarkers) {
            const present = bundle.text.includes(requirement.marker);
            if (!present) {
                fail(
                    `bundle mode: ${target} is missing "${requirement.marker}" (${requirement.engine}). ` +
                    `${requirement.why}`
                );
            }
            rows.push([requirement.marker, requirement.engine, present ? 'present' : 'MISSING']);
        }

        const detected = detectDecodeConcurrency(bundle.text);
        const expected = lock.expectedDecodeConcurrency;
        if (detected === null) {
            warn(
                `could not read the decode concurrency out of ${target}; ` +
                `expected ${expected} but the minified shape was not recognised`
            );
            rows.push(['decode concurrency', 'scratch-vm', 'not detected']);
        } else if (detected !== expected) {
            fail(`bundle mode: ${target} decodes images with concurrency ${detected}, expected ${expected}`);
            rows.push(['decode concurrency', 'scratch-vm', `${detected} (wanted ${expected})`]);
        } else {
            rows.push(['decode concurrency', 'scratch-vm', `${detected} ok`]);
        }

        printTable(['marker', 'engine', 'result'], rows);
    }
};

// ------------------------------------------------------------------- shared

const printTable = (headers, rows) => {
    const widths = headers.map((header, i) =>
        Math.max(header.length, ...rows.map(row => String(row[i]).length)));
    const line = row => row.map((cell, i) => String(cell).padEnd(widths[i])).join('  ');
    console.log(`  ${line(headers)}`);
    console.log(`  ${widths.map(width => '-'.repeat(width)).join('  ')}`);
    for (const row of rows) console.log(`  ${line(row)}`);
    console.log('');
};

const defaultBundleTargets = () => {
    const targets = [];
    const buildDir = path.join(ROOT, 'scratch-gui/build');
    if (fs.existsSync(buildDir)) targets.push(buildDir);
    const apkRoot = path.join(ROOT, 'android/app/build/outputs/apk');
    if (fs.existsSync(apkRoot)) {
        targets.push(...walk(apkRoot, file => file.endsWith('.apk')));
    }
    return targets;
};

const main = () => {
    const [mode, ...rest] = process.argv.slice(2);
    const lock = readJson(LOCK_PATH);

    if (mode === 'lock') {
        let guiDir = path.join(ROOT, 'scratch-gui');
        const dirIndex = rest.indexOf('--gui');
        if (dirIndex !== -1) guiDir = path.resolve(ROOT, normalisePath(rest[dirIndex + 1]));
        verifyLock(lock, guiDir);
    } else if (mode === 'bundle') {
        const targets = rest.filter(arg => !arg.startsWith('--'));
        verifyBundle(lock, targets.length > 0
            ? targets.map(t => path.resolve(ROOT, normalisePath(t)))
            : defaultBundleTargets());
    } else {
        console.error('Usage:');
        console.error('  node scripts/verify-engine.mjs lock   [--gui <scratch-gui dir>]');
        console.error('  node scripts/verify-engine.mjs bundle [<build dir or .apk>...]');
        process.exit(2);
    }

    for (const message of warnings) console.log(`[verify-engine] warning: ${message}`);
    if (warnings.length > 0) console.log('');

    if (failures.length > 0) {
        console.error('[verify-engine] FAILED');
        for (const message of failures) console.error(`  - ${message}`);
        console.error('');
        process.exit(1);
    }
    console.log('[verify-engine] ok - the shipped kernel matches engine-lock.json\n');
};

const isEntryPoint = Boolean(process.argv[1]) &&
    import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href;

if (isEntryPoint) main();
