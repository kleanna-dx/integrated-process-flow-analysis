module.exports = {
  apps: [
    {
      name: 'process-map-automation',
      script: './scripts/run-local.sh',
      cwd: '/home/user/webapp',
      interpreter: 'bash',
      watch: false,
      instances: 1,
      exec_mode: 'fork',
      autorestart: true,
      max_restarts: 5
    }
  ]
}
