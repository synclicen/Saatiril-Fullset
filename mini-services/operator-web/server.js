/**
 * SAATIRIL Operator Web — Static file server for Chrome-based operator camera
 */

const http = require('http')
const fs = require('fs')
const path = require('path')

const PORT = 3005
const STATIC_DIR = path.join(__dirname, '..', '..', 'public')

const MIME_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
}

const server = http.createServer((req, res) => {
  let url = (req.url || '/').split('?')[0]
  if (url === '/') url = '/operator.html'
  if (url.includes('..')) { res.writeHead(403); res.end('Forbidden'); return }
  
  const filePath = path.join(STATIC_DIR, url)
  
  if (!fs.existsSync(filePath)) {
    res.writeHead(404)
    res.end('Not found: ' + url)
    return
  }
  
  const ext = '.' + filePath.split('.').pop().toLowerCase()
  const contentType = MIME_TYPES[ext] || 'application/octet-stream'
  
  try {
    const data = fs.readFileSync(filePath)
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
  console.log(`[SAATIRIL OP-WEB] Server on port ${PORT}`)
  console.log(`[SAATIRIL OP-WEB] Static dir: ${STATIC_DIR}`)
  console.log(`[SAATIRIL OP-WEB] Access: http://<ip>/operator.html?XTransformPort=${PORT}`)
})

// Keep alive
setInterval(() => {}, 60000)
