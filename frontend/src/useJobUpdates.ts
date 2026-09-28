import { Client } from '@stomp/stompjs'
import { useEffect, useRef, useState } from 'react'
import { session, type Job } from './api'

/**
 * Subscribes to the user's job updates over STOMP. Returns whether the socket is live so callers
 * can fall back to polling while it isn't.
 */
export function useJobUpdates(onJob: (job: Job) => void): boolean {
  const [connected, setConnected] = useState(false)
  const handler = useRef(onJob)
  handler.current = onJob

  useEffect(() => {
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:'
    const client = new Client({
      brokerURL: `${proto}//${location.host}/ws`,
      reconnectDelay: 5000,
      // Re-read the token on every (re)connect; it rotates every 15 minutes.
      beforeConnect: async () => {
        client.connectHeaders = { Authorization: `Bearer ${session.get()?.accessToken ?? ''}` }
      },
      onConnect: () => {
        setConnected(true)
        client.subscribe('/user/queue/jobs', (msg) => handler.current(JSON.parse(msg.body) as Job))
      },
      onWebSocketClose: () => setConnected(false),
      onStompError: () => setConnected(false),
    })
    client.activate()
    return () => {
      void client.deactivate()
    }
  }, [])

  return connected
}
