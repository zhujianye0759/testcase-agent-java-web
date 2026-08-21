/// <reference types="node" />

import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const globalStyles = readFileSync('src/styles/global.css', 'utf8')
const tokens = readFileSync('src/styles/tokens.css', 'utf8')

describe('PC UI semantic foundations', () => {
  it('maps the source-confirmed palette without scattering replacement values', () => {
    const palette = {
      '--color-primary': '#155BD4',
      '--color-primary-hover': '#3A7DE0',
      '--color-primary-active': '#0940AD',
      '--color-primary-disabled': '#91C2FA',
      '--color-primary-bg': '#E6F3FF',
      '--color-bg-white': '#FFFFFF',
      '--color-bg-single': '#F2F4F6',
      '--color-bg-double': '#EDEFF1',
      '--color-border': '#EBEDEF',
      '--color-divider-input': '#E2E5E8',
      '--color-text-primary': '#1F1F1F',
      '--color-text-secondary': '#666666',
      '--color-text-tertiary': '#999999',
      '--color-text-placeholder': '#B9BBBF',
      '--color-success': '#2AB55E',
      '--color-warning': '#F28F15',
      '--color-error': '#F02525',
    }

    for (const [name, value] of Object.entries(palette)) {
      expect(tokens).toContain(`${name}: ${value}`)
    }
  })

  it('maps the confirmed spacing, radius, typography, and shell dimensions', () => {
    for (const value of [0, 4, 8, 16, 24, 32, 40, 48, 56, 64, 72, 80]) {
      expect(tokens).toContain(`--space-${value}: ${value}px`)
    }

    expect(tokens).toContain('--radius-sm: 2px')
    expect(tokens).toContain('--radius-md: 4px')
    expect(tokens).toContain('--radius-lg: 8px')
    expect(tokens).toContain('--type-h1: 600 24px/32px')
    expect(tokens).toContain('--type-body: 400 14px/22px')
    expect(tokens).toContain('--shell-top-height: 56px')
    expect(tokens).toContain('--shell-sidebar-expanded-width: 240px')
  })

  it('uses the content container for the 1200px layout rule and keeps focus visible', () => {
    expect(globalStyles).toContain('container-type: inline-size')
    expect(globalStyles).toContain('@container app-shell (min-width: 1200px)')
    expect(globalStyles).toContain('padding-inline: var(--space-16)')
    expect(globalStyles).toContain('padding-inline: var(--space-24)')
    expect(globalStyles).toContain(':focus-visible')
  })

  it('defines reusable restrained technology tokens and honours reduced motion', () => {
    for (const token of [
      '--color-tech-navy',
      '--color-tech-cyan',
      '--color-tech-grid',
      '--shadow-base',
      '--shadow-middle',
      '--motion-fast',
      '--motion-standard',
    ]) {
      expect(tokens).toContain(token)
    }

    expect(globalStyles).toContain('prefers-reduced-motion: reduce')
    expect(globalStyles).toContain('.app-shell__ambient')
  })

  // [Req-ID]: REQ-UIX-009
  it('defines the immersive dark mission-control semantic tokens', () => {
    for (const token of [
      '--color-shell-abyss',
      '--color-aurora-cyan',
      '--color-aurora-violet',
      '--color-glass-raised',
      '--color-glass-inset',
      '--color-glass-border',
      '--color-ink-primary',
      '--color-ink-secondary',
      '--color-ink-placeholder',
      '--color-accent-cyan',
      '--gradient-action-primary',
      '--gradient-text-heading',
      '--color-success-ink',
      '--color-warning-ink',
      '--color-error-ink',
      '--shadow-glow-action',
      '--shadow-glow-cyan',
    ]) {
      expect(tokens).toContain(token)
    }
  })

  // [Req-ID]: REQ-UIX-009
  it('renders native controls, focus, and ambience dark-aware with motion guards', () => {
    expect(globalStyles).toContain('color-scheme: dark')
    expect(globalStyles).toContain('accent-color')
    expect(globalStyles).toContain('backdrop-filter')
    expect(globalStyles).toContain('@keyframes aurora-drift')
    expect(globalStyles).toContain('@keyframes status-pulse')
    expect(globalStyles).toContain('prefers-reduced-motion: reduce')
  })

  // [Req-ID]: REQ-UIX-009
  it('anchors the mission-control layout: hero radar, step counters, sticky actions, custom controls', () => {
    expect(globalStyles).toContain('@keyframes radar-sweep')
    expect(globalStyles).toContain('.generation-workspace__hero-visual')
    expect(globalStyles).toContain('counter-reset: form-step')
    expect(globalStyles).toContain('position: sticky')
    expect(globalStyles).toContain('appearance: none')
  })

  // [Req-ID]: REQ-UIX-009 — grid backdrop replaced by starfield + meteor ambience
  it('replaces the grid backdrop with a twinkling starfield and meteor streaks', () => {
    expect(globalStyles).not.toContain('linear-gradient(90deg, var(--color-tech-grid)')
    expect(globalStyles).toContain('@keyframes star-twinkle')
    expect(globalStyles).toContain('@keyframes starfall')
    expect(globalStyles).toContain('@keyframes hero-sheen')
    expect(tokens).toContain('--color-star-bright')
  })

  // [Req-ID]: REQ-UIX-009 — reference-grade polish: border beam, display hero type, richer aurora
  it('adds border-beam selection, display-scale hero type, and a magenta aurora layer', () => {
    expect(globalStyles).toContain('@property --beam-angle')
    expect(globalStyles).toContain('@keyframes beam-spin')
    expect(globalStyles).toContain('font: var(--type-display-48)')
    expect(tokens).toContain('--color-aurora-magenta')
  })

  // [Req-ID]: REQ-UIX-009 — ui-ux-pro-max AI-native re-tone: violet brand, cyan interaction
  it('applies the AI-native violet brand story with staggered list motion', () => {
    expect(tokens).toContain('--color-accent-violet-deep: #6D28D9')
    expect(tokens).toContain('--gradient-edge-violet')
    expect(tokens).toContain('--gradient-action-primary: linear-gradient(135deg, var(--color-accent-violet-deep)')
    expect(globalStyles).toContain('@keyframes row-enter')
    expect(globalStyles).toContain('.task-list tbody tr:nth-child(2)')
  })
})
