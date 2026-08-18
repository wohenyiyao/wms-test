# =====================================================================
# WMS Backend API Smoke Test
# =====================================================================
# Rule: every time an API endpoint is implemented or modified, add its
#       cases to this script (see the sections below).
#
# Prerequisite: backend must be running first, e.g.
#   cd backend-java && mvn spring-boot:run
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File smoke-test.ps1
#   powershell -ExecutionPolicy Bypass -File smoke-test.ps1 -BaseUrl http://localhost:8080
#
# Exit code: 0 = all passed, 1 = at least one failed.
# Note: runs against the real DB; inbound-order cases append real rows
#       (order numbers keep incrementing) - acceptable for smoke tests.
# Note: script is ASCII-only on purpose so it parses correctly under
#       both Windows PowerShell 5.1 and PowerShell 7.
# =====================================================================
param([string]$BaseUrl = 'http://localhost:8080')

$ErrorActionPreference = 'Stop'
$script:pass = 0
$script:fail = 0
$script:failedCases = @()

function Invoke-Api {
    param([string]$Method, [string]$Path, $Body)
    $uri = "$BaseUrl$Path"
    $params = @{ Uri = $uri; Method = $Method; UseBasicParsing = $true; TimeoutSec = 10 }
    if ($null -ne $Body) {
        $params.Body = ($Body | ConvertTo-Json -Depth 10)
        $params.ContentType = 'application/json'
    }
    try {
        $r = Invoke-WebRequest @params
        $json = $null
        try { $json = $r.Content | ConvertFrom-Json } catch { $json = $null }
        return @{ Status = [int]$r.StatusCode; Json = $json; Raw = $r.Content }
    } catch {
        $status = 0
        $content = $null
        if ($_.Exception.Response) {
            $status = [int]$_.Exception.Response.StatusCode
            try {
                $sr = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
                $content = $sr.ReadToEnd()
                $sr.Close()
            } catch { $content = $null }
        }
        if (-not $content) { $content = $_.ErrorDetails.Message }
        $json = $null
        try { $json = $content | ConvertFrom-Json } catch { $json = $null }
        return @{ Status = $status; Json = $json; Raw = $content }
    }
}

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

function Test-Case {
    param([string]$Name, [scriptblock]$Assert)
    try {
        & $Assert
        $script:pass++
        Write-Host "  [PASS] $Name" -ForegroundColor Green
    } catch {
        $script:fail++
        $script:failedCases += $Name
        Write-Host "  [FAIL] $Name -> $($_.Exception.Message)" -ForegroundColor Red
    }
}

Write-Host "== WMS API Smoke Test @ $BaseUrl ==" -ForegroundColor Cyan

# ---------------------------------------------------------------------
# 1. Products (reference implementation - regression)
# ---------------------------------------------------------------------
Test-Case 'GET /api/products -> 200, code=200, non-empty list' {
    $r = Invoke-Api GET '/api/products'
    Assert-True ($r.Status -eq 200) "HTTP status = $($r.Status), expected 200"
    Assert-True ($r.Json.code -eq 200) "body.code = $($r.Json.code), expected 200"
    Assert-True ($null -ne $r.Json.data -and $r.Json.data.Count -ge 1) 'product list is empty'
}

Test-Case 'GET /api/products?keyword=SKU-001 -> filtered' {
    $r = Invoke-Api GET '/api/products?keyword=SKU-001'
    Assert-True ($r.Status -eq 200) "HTTP status = $($r.Status), expected 200"
    Assert-True ($r.Json.data.Count -eq 1) "expected 1 match, got $($r.Json.data.Count)"
    Assert-True ($r.Json.data[0].sku -eq 'SKU-001') "sku = $($r.Json.data[0].sku)"
}

# ---------------------------------------------------------------------
# 2. Warehouses & Locations (reference implementation - regression)
# ---------------------------------------------------------------------
Test-Case 'GET /api/warehouses -> 200, non-empty' {
    $r = Invoke-Api GET '/api/warehouses'
    Assert-True ($r.Status -eq 200) "HTTP status = $($r.Status), expected 200"
    Assert-True ($null -ne $r.Json.data -and $r.Json.data.Count -ge 1) 'warehouse list is empty'
}

Test-Case 'GET /api/warehouses/1/locations -> 200, non-empty' {
    $r = Invoke-Api GET '/api/warehouses/1/locations'
    Assert-True ($r.Status -eq 200) "HTTP status = $($r.Status), expected 200"
    Assert-True ($null -ne $r.Json.data -and $r.Json.data.Count -ge 1) 'location list is empty'
}

# ---------------------------------------------------------------------
# 3. POST /api/inbound-orders (Task 1) - happy path
# ---------------------------------------------------------------------
Test-Case 'POST /api/inbound-orders -> HTTP 201, body code=200, orderNo format IN-yyyyMMdd-XXX' {
    $body = @{ supplierName = 'SmokeSupplier'; items = @(@{ productId = 1; quantity = 7; locationCode = 'WH-A-01-01' }) }
    $r = Invoke-Api POST '/api/inbound-orders' $body
    Assert-True ($r.Status -eq 201) "HTTP status = $($r.Status), expected 201"
    Assert-True ($r.Json.code -eq 200) "body.code = $($r.Json.code), expected 200"
    Assert-True ($r.Json.data.orderNo -match '^IN-\d{8}-\d{3,}$') "orderNo = $($r.Json.data.orderNo)"
    Assert-True ($r.Json.data.status -eq 'COMPLETED') "status = $($r.Json.data.status)"
    Assert-True ($r.Json.data.items.Count -eq 1) 'items count mismatch'
    Assert-True ($r.Json.data.items[0].quantity -eq 7) 'item quantity mismatch'
    Assert-True ($r.Json.data.items[0].productName.Length -gt 0) 'productName empty'
}

Test-Case 'POST x2 -> orderNo sequence increments' {
    $body = @{ supplierName = 'SmokeSeq'; items = @(@{ productId = 2; quantity = 2; locationCode = 'WH-A-01-02' }) }
    $r1 = Invoke-Api POST '/api/inbound-orders' $body
    $r2 = Invoke-Api POST '/api/inbound-orders' $body
    Assert-True ($r1.Status -eq 201 -and $r2.Status -eq 201) "HTTP $($r1.Status)/$($r2.Status)"
    $seq1 = [int](($r1.Json.data.orderNo -split '-')[-1])
    $seq2 = [int](($r2.Json.data.orderNo -split '-')[-1])
    Assert-True ($seq2 -gt $seq1) "seq $seq1 -> $seq2 did not increment"
}

Test-Case 'POST duplicate product+location in one order -> 201, 2 items echoed' {
    $body = @{
        supplierName = 'SmokeDup'
        items = @(
            @{ productId = 3; quantity = 3; locationCode = 'WH-A-02-01' },
            @{ productId = 3; quantity = 4; locationCode = 'WH-A-02-01' }
        )
    }
    $r = Invoke-Api POST '/api/inbound-orders' $body
    Assert-True ($r.Status -eq 201) "HTTP status = $($r.Status), expected 201"
    Assert-True ($r.Json.data.items.Count -eq 2) 'expected 2 item rows in response'
}

Test-Case 'POST multi-item order -> 201, all items echoed' {
    $body = @{
        supplierName = 'SmokeMulti'
        items = @(
            @{ productId = 1; quantity = 1; locationCode = 'WH-A-01-01' },
            @{ productId = 2; quantity = 2; locationCode = 'WH-A-01-02' },
            @{ productId = 4; quantity = 3; locationCode = 'WH-A-01-01' }
        )
    }
    $r = Invoke-Api POST '/api/inbound-orders' $body
    Assert-True ($r.Status -eq 201) "HTTP status = $($r.Status), expected 201"
    Assert-True ($r.Json.data.items.Count -eq 3) 'expected 3 item rows'
}

Test-Case 'POST same requestId twice -> same orderNo (idempotent replay)' {
    $rid = 'smoke-' + (Get-Date -Format 'HHmmssfff') + '-' + (Get-Random -Minimum 100000 -Maximum 999999)
    $body = @{ supplierName = 'SmokeIdem'; requestId = $rid; items = @(@{ productId = 4; quantity = 2; locationCode = 'WH-A-01-01' }) }
    $r1 = Invoke-Api POST '/api/inbound-orders' $body
    $r2 = Invoke-Api POST '/api/inbound-orders' $body
    Assert-True ($r1.Status -eq 201 -and $r2.Status -eq 201) "HTTP $($r1.Status)/$($r2.Status)"
    Assert-True ($r1.Json.data.orderNo -eq $r2.Json.data.orderNo) "orderNo $($r1.Json.data.orderNo) != $($r2.Json.data.orderNo)"
}

# ---------------------------------------------------------------------
# 4. POST /api/inbound-orders - error handling
# ---------------------------------------------------------------------
Test-Case 'POST unknown product -> code=404, message non-empty' {
    $body = @{ supplierName = 'X'; items = @(@{ productId = 999; quantity = 1; locationCode = 'WH-A-01-01' }) }
    $r = Invoke-Api POST '/api/inbound-orders' $body
    Assert-True ($r.Json.code -eq 404) "body.code = $($r.Json.code), expected 404, raw=$($r.Raw)"
    Assert-True ($r.Json.message.Length -gt 0) 'error message empty'
}

Test-Case 'POST unknown location -> code=404, message non-empty' {
    $body = @{ supplierName = 'X'; items = @(@{ productId = 1; quantity = 1; locationCode = 'NO-SUCH-LOC' }) }
    $r = Invoke-Api POST '/api/inbound-orders' $body
    Assert-True ($r.Json.code -eq 404) "body.code = $($r.Json.code), expected 404, raw=$($r.Raw)"
    Assert-True ($r.Json.message.Length -gt 0) 'error message empty'
}

Test-Case 'POST empty items -> code=400 (validation)' {
    $body = @{ supplierName = 'X'; items = @() }
    $r = Invoke-Api POST '/api/inbound-orders' $body
    Assert-True ($r.Json.code -eq 400) "body.code = $($r.Json.code), expected 400, raw=$($r.Raw)"
}

Test-Case 'POST null quantity -> code=400 (regression @NotNull)' {
    $body = @{ supplierName = 'X'; items = @(@{ productId = 1; locationCode = 'WH-A-01-01' }) }
    $r = Invoke-Api POST '/api/inbound-orders' $body
    Assert-True ($r.Json.code -eq 400) "body.code = $($r.Json.code), expected 400, raw=$($r.Raw)"
}

Test-Case 'POST quantity=0 -> code=400 (@Min)' {
    $body = @{ supplierName = 'X'; items = @(@{ productId = 1; quantity = 0; locationCode = 'WH-A-01-01' }) }
    $r = Invoke-Api POST '/api/inbound-orders' $body
    Assert-True ($r.Json.code -eq 400) "body.code = $($r.Json.code), expected 400, raw=$($r.Raw)"
}

Test-Case 'POST blank supplier -> code=400' {
    $body = @{ supplierName = '  '; items = @(@{ productId = 1; quantity = 1; locationCode = 'WH-A-01-01' }) }
    $r = Invoke-Api POST '/api/inbound-orders' $body
    Assert-True ($r.Json.code -eq 400) "body.code = $($r.Json.code), expected 400, raw=$($r.Raw)"
}

Test-Case 'POST missing items field -> code=400' {
    $body = @{ supplierName = 'X' }
    $r = Invoke-Api POST '/api/inbound-orders' $body
    Assert-True ($r.Json.code -eq 400) "body.code = $($r.Json.code), expected 400, raw=$($r.Raw)"
}

# ---------------------------------------------------------------------
# 5. GET /api/inventory (Task 2) - inventory query
# ---------------------------------------------------------------------
Test-Case 'GET /api/inventory -> 200, PageResult structure' {
    $r = Invoke-Api GET '/api/inventory?page=1&pageSize=5'
    Assert-True ($r.Status -eq 200) "HTTP status = $($r.Status), expected 200"
    Assert-True ($r.Json.code -eq 200) "body.code = $($r.Json.code), expected 200"
    Assert-True ($null -ne $r.Json.data.list) 'data.list missing'
    Assert-True ($null -ne $r.Json.data.total) 'data.total missing'
    Assert-True ($r.Json.data.list.Count -le 5) "pageSize violated: got $($r.Json.data.list.Count)"
}

Test-Case 'GET /api/inventory?keyword=SKU-001 -> all rows match sku' {
    $r = Invoke-Api GET '/api/inventory?keyword=SKU-001'
    Assert-True ($r.Status -eq 200) "HTTP status = $($r.Status)"
    foreach ($row in $r.Json.data.list) {
        Assert-True ($row.sku -like '*SKU-001*') "row sku = $($row.sku), expected contains SKU-001"
    }
}

Test-Case 'GET /api/inventory?warehouseId=1 -> rows with warehouseName (WH-A)' {
    $r = Invoke-Api GET '/api/inventory?warehouseId=1'
    Assert-True ($r.Status -eq 200) "HTTP status = $($r.Status)"
    Assert-True ($r.Json.data.total -ge 1) 'warehouse 1 should have inventory rows'
    foreach ($row in $r.Json.data.list) {
        Assert-True ($row.warehouseName.Length -gt 0) 'warehouseName empty (join failed)'
    }
}

Test-Case 'GET /api/inventory?lowStockOnly=true -> all rows quantity<10' {
    $r = Invoke-Api GET '/api/inventory?lowStockOnly=true'
    Assert-True ($r.Status -eq 200) "HTTP status = $($r.Status)"
    foreach ($row in $r.Json.data.list) {
        Assert-True ($row.quantity -lt 10) "quantity = $($row.quantity), expected < 10"
    }
}

Test-Case 'GET /api/inventory?pageSize=9999 -> capped to 100 (availability)' {
    $r = Invoke-Api GET '/api/inventory?page=1&pageSize=9999'
    Assert-True ($r.Status -eq 200) "HTTP status = $($r.Status)"
    Assert-True ($r.Json.data.list.Count -le 100) "pageSize cap violated: got $($r.Json.data.list.Count)"
}

Test-Case 'GET /api/inventory deep offset (page=200&pageSize=100) -> code=400 (deep pagination guard)' {
    $r = Invoke-Api GET '/api/inventory?page=200&pageSize=100'
    Assert-True ($r.Status -eq 400) "HTTP status = $($r.Status), expected 400"
    Assert-True ($r.Json.code -eq 400) "body.code = $($r.Json.code), expected 400, raw=$($r.Raw)"
    Assert-True ($r.Json.message.Length -gt 0) 'error message empty'
}

# ---------------------------------------------------------------------
# 6. DELETE /api/products (Task 3) - reference check before delete
# ---------------------------------------------------------------------
Test-Case 'DELETE product with inventory -> code=400 (referential check), product kept' {
    # 用种子商品 SKU-001（有库存），删除应被拒绝
    $r = Invoke-Api DELETE '/api/products/1'
    Assert-True ($r.Json.code -eq 400) "body.code = $($r.Json.code), expected 400, raw=$($r.Raw)"
    Assert-True ($r.Json.message.Length -gt 0) 'error message empty'
    # 商品应仍在
    $g = Invoke-Api GET '/api/products/1'
    Assert-True ($g.Status -eq 200) "product still exists: HTTP $($g.Status)"
}

Test-Case 'DELETE brand-new product without references -> 200, deleted' {
    # 新建一个无任何关联的商品，删除应成功
    $sku = 'SMOKE-DEL-' + (Get-Random -Minimum 100000 -Maximum 999999)
    $c = Invoke-Api POST '/api/products' @{ name = 'SmokeDeleteMe'; sku = $sku; unit = '个' }
    Assert-True ($c.Status -eq 200) "create HTTP = $($c.Status)"
    $id = $c.Json.data.id
    $d = Invoke-Api DELETE "/api/products/$id"
    Assert-True ($d.Status -eq 200) "delete HTTP = $($d.Status)"
    Assert-True ($d.Json.code -eq 200) "delete body.code = $($d.Json.code)"
    # 已删除
    $g = Invoke-Api GET "/api/products/$id"
    Assert-True ($g.Json.code -eq 404) "deleted product should be 404, got code=$($g.Json.code)"
}

# ---------------------------------------------------------------------
# 7. POST /api/outbound-orders (Optional A) - outbound + oversell guard
# ---------------------------------------------------------------------
# Prepare a dedicated product for outbound smoke cases (repeatable:
# each case tops up 10 then deducts 10, so stock stays stable).
$outSku = 'SMOKE-OUT-1'
$outFound = Invoke-Api GET "/api/products?keyword=$outSku"
if ($outFound.Json.data.Count -gt 0) {
    $script:outPid = $outFound.Json.data[0].id
} else {
    $outCreated = Invoke-Api POST '/api/products' @{ name = 'SmokeOutbound'; sku = $outSku; unit = '个' }
    $script:outPid = $outCreated.Json.data.id
}
Assert-True ($null -ne $script:outPid) 'failed to prepare outbound test product'

Test-Case 'GET /api/inventory?productId=<id> -> rows filtered by product' {
    # top up 10 first (the dedicated product may have zero stock at this point),
    # then deduct 10 again to keep the stock balanced for repeatable runs.
    $ridIn = 'in-' + (Get-Date -Format 'HHmmssfff') + '-' + (Get-Random -Minimum 100000 -Maximum 999999)
    $inBody = @{ supplierName = 'SmokeOut'; requestId = $ridIn; items = @(@{ productId = $script:outPid; quantity = 10; locationCode = 'WH-A-01-01' }) }
    $null = Invoke-Api POST '/api/inbound-orders' $inBody

    $r = Invoke-Api GET "/api/inventory?productId=$script:outPid&pageSize=100"
    Assert-True ($r.Status -eq 200) "HTTP status = $($r.Status), expected 200"
    Assert-True ($r.Json.data.total -ge 1) 'no inventory rows for this product'
    foreach ($row in $r.Json.data.list) {
        Assert-True ($row.productId -eq $script:outPid) "row.productId = $($row.productId), expected $script:outPid"
    }

    # balance: deduct the 10 just topped up
    $ridOut = 'out-' + (Get-Date -Format 'HHmmssfff') + '-' + (Get-Random -Minimum 100000 -Maximum 999999)
    $outBody = @{ customerName = 'SmokeBalance'; requestId = $ridOut; items = @(@{ productId = $script:outPid; quantity = 10; locationCode = 'WH-A-01-01' }) }
    $bal = Invoke-Api POST '/api/outbound-orders' $outBody
    Assert-True ($bal.Status -eq 201) "balance outbound HTTP = $($bal.Status), expected 201"
}

Test-Case 'POST /api/outbound-orders -> HTTP 201, body code=200, orderNo format OUT-yyyyMMdd-XXX' {
    # top up 10 so the case is repeatable
    $ridIn = 'in-' + (Get-Date -Format 'HHmmssfff') + '-' + (Get-Random -Minimum 100000 -Maximum 999999)
    $inBody = @{ supplierName = 'SmokeOut'; requestId = $ridIn; items = @(@{ productId = $script:outPid; quantity = 10; locationCode = 'WH-A-01-01' }) }
    $null = Invoke-Api POST '/api/inbound-orders' $inBody

    $body = @{ customerName = 'SmokeCustomer'; requestId = ('out-' + (Get-Date -Format 'HHmmssfff') + '-' + (Get-Random -Minimum 100000 -Maximum 999999)); items = @(@{ productId = $script:outPid; quantity = 10; locationCode = 'WH-A-01-01' }) }
    $r = Invoke-Api POST '/api/outbound-orders' $body
    Assert-True ($r.Status -eq 201) "HTTP status = $($r.Status), expected 201"
    Assert-True ($r.Json.code -eq 200) "body.code = $($r.Json.code), expected 200"
    Assert-True ($r.Json.data.orderNo -match '^OUT-\d{8}-\d{3,}$') "orderNo = $($r.Json.data.orderNo)"
    Assert-True ($r.Json.data.status -eq 'COMPLETED') "status = $($r.Json.data.status)"
    Assert-True ($r.Json.data.items.Count -eq 1) 'items count mismatch'
    Assert-True ($r.Json.data.items[0].quantity -eq 10) 'item quantity mismatch'
}

Test-Case 'POST outbound same requestId twice -> same orderNo (idempotent replay)' {
    $ridIn = 'in-' + (Get-Date -Format 'HHmmssfff') + '-' + (Get-Random -Minimum 100000 -Maximum 999999)
    $inBody = @{ supplierName = 'SmokeOut'; requestId = $ridIn; items = @(@{ productId = $script:outPid; quantity = 10; locationCode = 'WH-A-01-01' }) }
    $null = Invoke-Api POST '/api/inbound-orders' $inBody

    $ridOut = 'out-' + (Get-Date -Format 'HHmmssfff') + '-' + (Get-Random -Minimum 100000 -Maximum 999999)
    $body = @{ customerName = 'SmokeIdem'; requestId = $ridOut; items = @(@{ productId = $script:outPid; quantity = 10; locationCode = 'WH-A-01-01' }) }
    $r1 = Invoke-Api POST '/api/outbound-orders' $body
    $r2 = Invoke-Api POST '/api/outbound-orders' $body
    Assert-True ($r1.Status -eq 201 -and $r2.Status -eq 201) "HTTP $($r1.Status)/$($r2.Status)"
    Assert-True ($r1.Json.data.orderNo -eq $r2.Json.data.orderNo) "orderNo $($r1.Json.data.orderNo) != $($r2.Json.data.orderNo)"
}

Test-Case 'POST outbound qty > stock -> code=400 (oversell guard)' {
    $body = @{ customerName = 'SmokeOver'; requestId = ('out-' + (Get-Date -Format 'HHmmssfff') + '-' + (Get-Random -Minimum 100000 -Maximum 999999)); items = @(@{ productId = $script:outPid; quantity = 999999; locationCode = 'WH-A-01-01' }) }
    $r = Invoke-Api POST '/api/outbound-orders' $body
    Assert-True ($r.Status -eq 400) "HTTP status = $($r.Status), expected 400"
    Assert-True ($r.Json.code -eq 400) "body.code = $($r.Json.code), expected 400, raw=$($r.Raw)"
    Assert-True ($r.Json.message.Length -gt 0) 'error message empty'
}

# ---------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------
Write-Host ""
Write-Host "== Result: $script:pass passed / $script:fail failed ==" -ForegroundColor Cyan
if ($script:fail -gt 0) {
    Write-Host "Failed cases: $($script:failedCases -join ' | ')" -ForegroundColor Red
    exit 1
}
Write-Host "All smoke tests passed." -ForegroundColor Green
exit 0
