import { describe, it, expect } from 'vitest'
import { EventEmitter } from './events.js'

type TestEvents = {
  'ping': { value: number }
  'pong': { message: string }
}

describe('EventEmitter', () => {
  it('delivers events to listeners', () => {
    const emitter = new EventEmitter<TestEvents>()
    const received: { value: number }[] = []

    emitter.on('ping', (data) => { received.push(data) })
    emitter.emit('ping', { value: 42 })

    expect(received).toEqual([{ value: 42 }])
  })

  it('delivers to multiple listeners', () => {
    const emitter = new EventEmitter<TestEvents>()
    let count = 0

    emitter.on('ping', () => { count++ })
    emitter.on('ping', () => { count++ })
    emitter.emit('ping', { value: 1 })

    expect(count).toBe(2)
  })

  it('unsubscribe via returned function stops delivery', () => {
    const emitter = new EventEmitter<TestEvents>()
    let count = 0

    const unsub = emitter.on('ping', () => { count++ })
    emitter.emit('ping', { value: 1 })
    expect(count).toBe(1)

    unsub()
    emitter.emit('ping', { value: 2 })
    expect(count).toBe(1)
  })

  it('off() removes a specific listener', () => {
    const emitter = new EventEmitter<TestEvents>()
    let count = 0
    const listener = () => { count++ }

    emitter.on('ping', listener)
    emitter.emit('ping', { value: 1 })
    expect(count).toBe(1)

    emitter.off('ping', listener)
    emitter.emit('ping', { value: 2 })
    expect(count).toBe(1)
  })

  it('emit with no listeners does not throw', () => {
    const emitter = new EventEmitter<TestEvents>()
    expect(() => emitter.emit('ping', { value: 1 })).not.toThrow()
  })

  it('keeps events independent', () => {
    const emitter = new EventEmitter<TestEvents>()
    const pings: number[] = []
    const pongs: string[] = []

    emitter.on('ping', (d) => { pings.push(d.value) })
    emitter.on('pong', (d) => { pongs.push(d.message) })

    emitter.emit('ping', { value: 1 })
    emitter.emit('pong', { message: 'hello' })

    expect(pings).toEqual([1])
    expect(pongs).toEqual(['hello'])
  })
})
