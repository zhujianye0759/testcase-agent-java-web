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
})
