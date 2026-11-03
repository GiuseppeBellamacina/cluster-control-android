#!/bin/bash
# Integration test entry: a throwaway sshd + fake SLURM, then the ClusterIntegrationTest against it.
set -e
ssh-keygen -q -t ed25519 -N '' -f /tmp/it_key
install -d -m 700 -o tester -g tester /home/tester/.ssh
install -m 600 -o tester -g tester /tmp/it_key.pub /home/tester/.ssh/authorized_keys
/usr/sbin/sshd -p 2222 -o PasswordAuthentication=no -o PubkeyAuthentication=yes

rsync -a --delete --exclude build --exclude .gradle --exclude .kotlin --exclude dist --exclude .git /src/ /work/
cd /work
export CC_IT_HOST=127.0.0.1 CC_IT_PORT=2222 CC_IT_USER=tester CC_IT_KEY_FILE=/tmp/it_key CC_IT_REPO_DIR=/t2g
set +e
gradle --no-daemon --console=plain :core:test --tests '*ClusterIntegrationTest*' -i > /tmp/it.log 2>&1
rc=$?
set -e
grep -E 'ClusterIntegrationTest|PASSED|FAILED|SKIPPED|BUILD|Exception|expected|Error|\.kt:[0-9]+' /tmp/it.log | head -40
[ $rc -eq 0 ] && echo INTEGRATION_OK
exit $rc
