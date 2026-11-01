# ZetraAdmin 1.3.0 - moderacao SQL e analytics pelo mesmo canal seguro

Esta versao preserva o comando `/adm`, os limites, os logs e a integracao atual com
o painel. A mesma configuracao `panel-sync` e o mesmo token passam a transportar
moderacao e analytics, sem expor o banco MySQL ao servidor Minecraft.

## Analytics integrado

- jogadores novos e recorrentes;
- DAU, WAU, MAU e DAU/MAU;
- sessoes, tempo total, tempo medio por sessao e por jogador;
- retencao D1 ate D7, geral, Java e Bedrock;
- CCU total, Java e Bedrock em snapshots configuraveis;
- funil de 10 e 30 minutos;
- comandos permitidos e eventos customizados;
- inicio, parada e deteccao simples de desligamento inesperado;
- fila local persistente: se o painel cair, os eventos sao enviados depois.

O plugin nao coleta mensagens, argumentos de comandos, IPs ou conteudo privado.
Somente o nome dos comandos incluidos na whitelist e enviado.

Outros plugins podem registrar eventos sem acessar banco ou token:

```java
ZetraAdminAnalyticsAPI.trackEvent(player, "daily_claim", "retention", 1, "day_3");
```

## Moderacao preservada

- ban e unban pelo painel;
- sincronizacao de `/adm ban`, `/adm tempban` e `/adm unban`;
- banco SQLite transacional em `plugins/ZetraAdmin/moderation.db`;
- migracao automatica e unica do antigo `moderation-cache.yml`;
- snapshot autoritativo do painel no startup e em intervalo configuravel;
- fila local para continuar funcionando quando o painel estiver offline;
- identificacao principal por UUID;
- verificacao adicional pelo nick normalizado para jogadores Bedrock/proxy cujo UUID mudou;
- bans exclusivos do cache do ZetraAdmin, sem manter entradas na BanList vanilla;
- busca por nick usando jogador online, cache proprio e cache seguro do Paper/Leaf;
- nenhuma chamada a `Bukkit.getOfflinePlayers()` ao banir, desbanir ou sincronizar;
- gravacao de `/adm ban` e `/adm unban` fora da thread principal;
- migracao antiga do Bukkit/Essentials desativada por padrao e fora do ciclo de 5 segundos.
- unban pelo painel e por `/adm unban` limpa somente o registro exato de UUID/nick
  que ainda possa existir no Bukkit/Essentials, sem listar todos os jogadores;
- o painel so recebe a confirmacao do unban depois dessa limpeza pontual terminar.

## Instalacao

1. Pare o servidor Minecraft de forma normal.
2. Substitua o JAR antigo por `ZetraAdmin.jar`.
3. Preserve o `config.yml` atual. As novas opcoes possuem valores seguros mesmo quando
   ainda nao existem no arquivo: `legacy-ban-migration.enabled=false` e
   `compatibility.clear-legacy-ban-on-unban=true`.
4. No primeiro startup, mantenha `moderation-cache.yml`: ele sera importado para
   `moderation.db` e preservado como `moderation-cache.migrated-backup.yml`.
5. Mantenha `staff-limits.yml`; essa atualizacao nao altera os limites existentes.
6. Inicie o servidor e confira no console `ZetraAdmin 1.3.0 habilitado`, a
   abertura do banco SQL e a quantidade recebida no snapshot do painel.

Se o plugin separado `ZetraAnalytics.jar` ainda estiver instalado, remova-o ao
migrar para esta versao para nao contar a mesma sessao duas vezes. Os dados antigos
nas tabelas `za_*` continuam preservados.

Nao use `/reload` para atualizar o plugin. O painel e o servidor Minecraft podem
ficar indisponiveis um para o outro sem liberar jogadores banidos.

O painel e a fonte autoritativa dos bans. Uma falha HTTP nao limpa o banco local:
o plugin continua aplicando o ultimo estado SQL valido e tenta sincronizar depois.
O campo `panel-sync.full-sync-seconds` controla a reconciliacao completa e usa 60
segundos por padrao.

## Comandos preservados

Todos os subcomandos antigos de `/adm` continuam iguais. O unban correto e
`/adm unban <jogador>`, usando a permissao `zetraAdmin.ban`.

Os comandos `/adm banip` e `/adm tempbanip` permanecem desativados porque dependem
da BanList vanilla. Para compatibilidade, `/unban <jogador>` e
`/essentials:unban <jogador>` sao redirecionados para `/adm unban <jogador>` antes
de o Essentials tocar na BanList. O painel continua usando o mesmo cache seguro.
`/ban`, `/tempban`, `/essentials:ban`, `/essentials:tempban`, `/minecraft:ban`
e `/minecraft:pardon` tambem usam `/adm`, com a permissao `zetraAdmin.ban` e
os limites existentes. Ban por IP permanece fora desse redirecionamento.

## Correcao 1.3.0

- Moderacao local convertida de YAML para SQLite com WAL e transacoes.
- Os bans ativos sao reconstruidos automaticamente pelo snapshot SQL do painel.
- Snapshot invalido, incompleto ou indisponivel nunca remove bans locais.
- Bans ausentes no banco local voltam no startup sem reenviar centenas de comandos.
- Unbans legitimos permanecem revogados porque o painel envia somente o estado ativo.
- `Somente validado` aparece apenas em jogador consultado manualmente no painel.

## Correcao 1.2.1

- Login e entrada no mundo verificam novamente o ban usando a identidade final.
- Confirmacao do comando aguarda a execucao do kick na thread principal.
- Cancelamentos de kick sao registrados, sem forcar cancelamentos de outros plugins.
- Repetir um comando que falhou nao transforma a falha em sucesso.
- Falha no envio de analytics nao impede a tentativa de buscar bans/unbans.
- Unban tambem limpa o ban legado do UUID/nick confirmado quando o cache esta sem ban.

Cada servidor deve ter um `panel-sync.server-id` distinto. Duas instancias com
o mesmo ID disputam a fila: uma pode confirmar um ban sem atingir o jogador
conectado na outra. Mantenha `semi-anarquia` somente no principal; no teste,
remova o plugin ou desative `panel-sync.enabled` enquanto nao houver integracao
separada no painel. O ID e o token existentes do principal continuam iguais.

## Migracao antiga opcional

`legacy-ban-migration.enabled` deve permanecer `false` em producao. Ela existe
somente para uma migracao controlada de bans vanilla antigos e, quando ativada,
roda uma unica vez durante a inicializacao, nunca a cada sincronizacao.

## Arquivos locais

- `moderation.db`: bans, jogadores conhecidos, comandos aplicados, metadados e fila offline.
- `moderation-cache.migrated-backup.yml`: copia preservada do cache antigo apos a migracao.
- `moderation-cache.yml`: lido somente durante a primeira migracao, sem continuar como banco ativo.
- `staff-limits.yml`: limites antigos, sem migracao ou sobrescrita.
- `logs/`: logs antigos do ZetraAdmin, sem mudanca.

O token da API e secreto. Nao publique o `config.yml` de producao.
