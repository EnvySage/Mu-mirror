// 后端流水线（Mu-mirror-B）
//
// 触发：仓库 webhook push（ai / main 分支）或手动"立即构建"
// 部署：产物入 releases/<时间戳> → 备份 DB + 重放幂等迁移 → 原子切链 → 重启 → 探活
//      探活失败由 backend-release.sh 内部自动切回上一版并重启（脚本自己负责回滚）
//
// 2C2G 注意：不能用 Jenkins 的并行 stage，也不能并发构建 —— 见下面 options。
pipeline {
  agent any

  parameters {
    // 2C2G 上默认跳过测试（surefire 另起 JVM，峰值多 200~300MB）。
    // 本地 / 更高配的机器上勾选打开即可。
    booleanParam(name: 'RUN_TESTS', defaultValue: false,
                 description: '是否在构建时跑 200 个单测（2C2G 建议关闭）')
  }

  options {
    timestamps()
    disableConcurrentBuilds()          // 并行构建 = 必 OOM，会连累同机 PG / 另一个项目
    buildDiscarder(logRotator(numToKeepStr: '20'))
    timeout(time: 30, unit: 'MINUTES')
  }

  environment {
    MIRROR_HOME = '/opt/mirror'
    // 限制 Maven 堆：默认按物理内存推算，2G 上会直接打满
    MAVEN_OPTS = '-Xmx512m -XX:MaxMetaspaceSize=256m'
  }

  stages {
    stage('构建') {
      steps {
        sh "./mvnw -B clean package ${params.RUN_TESTS ? '' : '-DskipTests'}"
      }
    }

    stage('发布') {
      steps {
        sh 'bash deploy/backend-release.sh'
      }
    }
  }

  post {
    success { echo '后端发布成功' }
    failure {
      // 探活失败的回滚已经在 backend-release.sh 里做完了，这里只做提示，别再触发一次回滚
      echo '后端发布失败：请确认 current 软链已指回上一版，并查看 /opt/mirror/shared/logs/backend.err.log'
    }
  }
}
