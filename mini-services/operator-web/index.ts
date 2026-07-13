/**
 * SAATIRIL Operator Web — Static file server for Chrome-based operator camera
 * 
 * This serves operator.html as a standalone web page that the operator
 * opens in Google Chrome on their Android phone. Chrome's native WebRTC
 * getUserMedia API has built-in UVC support, so USB HDMI video capture
 * cards work natively without any APK.
 * 
 * Port: 3005
 * Access: http://<server-ip>/?XTransformPort=3005
 * Direct: http://<server-ip>:3005/operator.html
 */

import { createServer, IncomingMessage, ServerResponse } from 'http'
import { readFileSync, existsSync } from 'fs'
import { join } from 'path'

const PORT = 3005
const STATIC_DIR = join(import.meta.dir, '..', '..', 'public')

const MIME_TYPES: Record<string, string> = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
}

const server = createServer((req: IncomingMessage, res: ServerResponse) => {
  // Parse URL, remove query params
  let url = (req.url || '/').split('?')[0]
  
  // Default to operator.html
  if (url === '/') url = '/operator.html'
  
  // Security: prevent directory traversal
  if (url.includes('..')) {
    res.writeHead(403)
    res.end('Forbidden')
    return
  }
  
  const filePath = join(STATIC_DIR, url)
  
  if (!existsSync(filePath)) {
    res.writeHead(404)
    res.end('Not found')
    return
  }
  
  const ext = '.' + filePath.split('.').pop()!.toLowerCase()
  const contentType = MIME_TYPES[ext] || 'application/octet-stream'
  
  try {
    const data = readFileSync(filePath)
    res.writeHead(200, {
      'Content-Type': contentType,
      'Cache-Control': 'no-cache',
      'Access-Control-Allow-Origin': '*',
    })
    res.end(data)
  } catch (err) {
    res.writeHead(500)
    res.end('Server error')
  }
})

server.listen(PORT, '0.0.0.0', () => {
  console.log(`[SAATIRIL OP-WEB] ═══════════════════════════════════════`)
  console.log(`[SAATIRIL OP-WEB]  Operator Web Server — Chrome Camera`)
  console.log(`[SAATIRIL OP-WEB]  Port: ${PORT}`)
  console.log(`[SAATIRIL OP-WEB]  Static dir: ${STATIC_DIR}`)
  console.log(`[SAATIRIL OP-WEB]  Access via gateway: http://<ip>/?XTransformPort=${PORT}`)
  console.log(`[SAATIRIL OP-WEB] ═══════════════════════════════════════`)
})
