#!/usr/bin/env node
/**
 * Check out the exact scratch-gui commit named in engine-lock.json.
 *
 * The Android app is a shell around scratch-gui, so "which kernel am I shipping"
 * is really "which scratch-gui commit did I build, and which engine revisions
 * does its pnpm-lock.yaml resolve". Cloning a floating `develop` and hoping
 * makes the answer depend on the moment CI ran -- which is how the 2.0.2 APK
 * shipped pre-fix engines with a green pipeline.
 *
 * This fetches the pinned commit by SHA instead, so the engine revisions are
 * fixed before a single byte is compiled. Verify them afterwards with
 * scripts/verify-engine.mjs.
 *
 * Usage:
 *   node scripts/fetch-scratch-gui.mjs [--dir scratch-gui] [--quiet]
 */

import {spawnSync} from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

import {normalisePath} from './lib/win-path.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const LOCK_PATH = path.join(ROOT, 'engine-lock.json');

const fail = message => {
    console.error(`\n[fetch-scratch-gui] ${message}\n`);
    process.exit(1);
};

const git = (args, cwd) => spawnSync('git', args, {
    cwd,
    encoding: 'utf8',
    env: {...process.env, GIT_TERMINAL_PROMPT: '0'}
});

/** @returns {string|null} trimmed stdout, or null when the command failed */
const gitOk = (args, cwd) => {
    const result = git(args, cwd);
    return result.status === 0 ? (result.stdout || '').trim() : null;
};

const parseArgs = () => {
    const argv = process.argv.slice(2);
    const opts = {dir: 'scratch-gui', quiet: false};
    for (let i = 0; i < argv.length; i++) {
        if (argv[i] === '--dir') opts.dir = argv[++i];
        else if (argv[i] === '--quiet') opts.quiet = true;
        else if (argv[i] === '--help' || argv[i] === '-h') {
            console.log('Usage: node scripts/fetch-scratch-gui.mjs [--dir <path>] [--quiet]');
            process.exit(0);
        } else fail(`unknown argument: ${argv[i]}`);
    }
    return opts;
};

const readLock = () => {
    if (!fs.existsSync(LOCK_PATH)) fail(`engine-lock.json not found at ${LOCK_PATH}`);
    try {
        return JSON.parse(fs.readFileSync(LOCK_PATH, 'utf8'));
    } catch (e) {
        fail(`engine-lock.json is not valid JSON: ${e.message}`);
    }
};

const explainMissingSha = sha => fail(
    `Could not fetch scratch-gui commit ${sha}.\n\n` +
    'The pinned commit is not reachable on the remote. A pinned SHA has to exist\n' +
    'on GitHub before CI can build it, so push the commit the pin points at:\n\n' +
    '    git -C <your scratch-gui clone> push origin develop\n\n' +
    'If the pin is stale rather than unpushed, refresh it with:\n\n' +
    '    node scripts/pin-scratch-gui.mjs --gui <your scratch-gui clone>'
);

const main = () => {
    const {dir, quiet} = parseArgs();
    const lock = readLock();
    const {repository: repo, sha} = lock.scratchGui || {};

    if (!repo || !/^[0-9a-f]{40}$/.test(sha || '')) {
        fail('engine-lock.json must define scratchGui.repository and a full 40-character scratchGui.sha');
    }

    const target = path.resolve(ROOT, normalisePath(dir));
    const log = message => {
        if (!quiet) console.log(message);
    };

    if (fs.existsSync(path.join(target, '.git'))) {
        const dirty = gitOk(['status', '--porcelain'], target);
        if (dirty === null) fail(`git status failed in ${target}`);
        if (dirty.length > 0) {
            fail(
                `${target} has uncommitted changes. Refusing to touch it.\n` +
                'Use a dedicated build checkout (the default scratch-gui/ inside this\n' +
                'repo), or commit/stash there first.'
            );
        }
    } else {
        if (fs.existsSync(target) && fs.readdirSync(target).length > 0) {
            fail(`${target} exists, is not a git checkout, and is not empty. ` +
                'Remove it, or point --dir somewhere else.');
        }
        fs.mkdirSync(target, {recursive: true});
        log(`[fetch-scratch-gui] initialising a fresh checkout in ${target}`);
        if (gitOk(['init', '--quiet'], target) === null) fail(`git init failed in ${target}`);
    }

    if (gitOk(['remote', 'get-url', 'origin'], target) === null) {
        if (gitOk(['remote', 'add', 'origin', repo], target) === null) {
            fail(`git remote add origin ${repo} failed in ${target}`);
        }
    } else if (gitOk(['remote', 'set-url', 'origin', repo], target) === null) {
        fail(`git remote set-url origin ${repo} failed in ${target}`);
    }

    if (gitOk(['rev-parse', 'HEAD'], target) === sha) {
        log(`[fetch-scratch-gui] already at ${sha}`);
        return;
    }

    log(`[fetch-scratch-gui] fetching ${sha}`);
    let fetched = git(['fetch', '--depth=1', 'origin', sha], target);
    if (fetched.status !== 0) {
        log('[fetch-scratch-gui] shallow fetch failed, retrying with full history for that commit');
        fetched = git(['fetch', 'origin', sha], target);
    }
    if (fetched.status !== 0) {
        const stderr = (fetched.stderr || '').trim();
        if (stderr) console.error(stderr);
        explainMissingSha(sha);
    }

    if (gitOk(['checkout', '--quiet', '--detach', 'FETCH_HEAD'], target) === null) {
        fail(`git checkout FETCH_HEAD failed in ${target}`);
    }
    const head = gitOk(['rev-parse', 'HEAD'], target);
    if (head !== sha) fail(`expected HEAD to be ${sha} but it is ${head}`);

    log(`[fetch-scratch-gui] scratch-gui is at ${sha}`);
};

main();
